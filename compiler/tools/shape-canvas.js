/* Shared shape graph layout and SVG renderer. No network or viewer state. */
(function () {
'use strict';
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function pointsToPath(pts, r = 8) {
  if (!pts || pts.length === 0) return '';
  if (pts.length === 1) return `M${pts[0].x},${pts[0].y}`;
  let d = `M${pts[0].x},${pts[0].y}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const prev = pts[i - 1], cur = pts[i], next = pts[i + 1];
    const lin = Math.hypot(cur.x - prev.x, cur.y - prev.y);
    const lout = Math.hypot(next.x - cur.x, next.y - cur.y);
    const rr = Math.min(r, lin / 2, lout / 2);
    if (rr < 0.5) { d += ` L${cur.x},${cur.y}`; continue; }
    const ax = cur.x - (cur.x - prev.x) / lin * rr, ay = cur.y - (cur.y - prev.y) / lin * rr;
    const bx = cur.x + (next.x - cur.x) / lout * rr, by = cur.y + (next.y - cur.y) / lout * rr;
    d += ` L${ax},${ay} Q${cur.x},${cur.y} ${bx},${by}`;
  }
  const last = pts[pts.length - 1];
  d += ` L${last.x},${last.y}`;
  return d;
}

function edgeLabelText(e) {
  const msgList = [...e.msgs.entries()].sort((a, b) => b[1] - a[1]);
  const top = msgList[0] ? msgList[0][0] : '?';
  return top + (msgList.length > 1 ? ` +${msgList.length - 1}` : '');
}
function edgeLabelWidth(text) { return text.length * 7.6 + 12; }
// Pairs A->B with B->A into one link, drawn as one line with an arrowhead at
// each end and a label per direction. A layered layout has to draw one edge
// of every two-way pair against the flow, and merging removes those back
// edges. The heavier direction orients the ELK edge and owns the first
// label. Returns [{ e, rev }], where rev is the reverse edge or null.
function pairEdges(edges) {
  const byPair = new Map();
  const links = [];
  for (const e of edges) {
    // Observation edges never pair. Each cross-atom read gets its own ELK
    // link so the obs name reaches ELK as its own label, and `rev`-side
    // paint paths (two-way markers, label flip) stay untouched for them.
    if (e.kind === 'obs') { links.push({ e, rev: null }); continue; }
    if (e.src === e.dst) { links.push({ e, rev: null }); continue; }
    const k = e.src < e.dst ? e.src + '|' + e.dst : e.dst + '|' + e.src;
    const link = byPair.get(k);
    if (!link) { const l = { e, rev: null }; byPair.set(k, l); links.push(l); continue; }
    if (e.total > link.e.total) { link.rev = link.e; link.e = e; } else link.rev = e;
  }
  return links;
}
// Arrow for the direction of travel along pts (first point to last) at the
// point nearest (x, y). Two-way labels use it to show which way their
// messages go.
const flipGlyph = { '▼': '▲', '▲': '▼', '▶': '◀', '◀': '▶' };
function travelGlyph(pts, x, y) {
  let best = null, bestD = Infinity;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1], b = pts[i];
    const dx = b.x - a.x, dy = b.y - a.y, L = dx * dx + dy * dy;
    if (!L) continue;
    const t = Math.max(0, Math.min(1, ((x - a.x) * dx + (y - a.y) * dy) / L));
    const d = Math.hypot(x - (a.x + t * dx), y - (a.y + t * dy));
    if (d < bestD) { bestD = d; best = { dx, dy }; }
  }
  if (!best) return '▼';
  return Math.abs(best.dy) >= Math.abs(best.dx) ? (best.dy > 0 ? '▼' : '▲') : (best.dx > 0 ? '▶' : '◀');
}


const layoutMemo = new WeakMap();
const algorithms = ['layered', 'stress', 'force', 'mrtree', 'radial', 'disco'];
const directions = ['DOWN', 'UP', 'RIGHT', 'LEFT'];
const usesDirection = algorithm => algorithm === 'layered' || algorithm === 'mrtree';
const capabilities = new WeakMap();
function availableAlgorithms(elk) {
  if (!capabilities.has(elk)) capabilities.set(elk, elk.knownLayoutAlgorithms()
    .then(list => new Set(list.map(a => a.id.split('.').pop()))));
  return capabilities.get(elk);
}

// The alternatives cannot lay out cross-hierarchy edges. Lay out each
// ownership group from the inside out, projecting connections to its direct
// children so those connections still influence the positions. The renderer
// restores every original connection between the actual atom pills.
async function layoutGroups(elk, graph, allEdges, algorithm) {
  const children = [];
  const groupOf = new Map();
  for (const child of graph.children || []) {
    const placed = child.children ? await layoutGroups(elk, child, allEdges, algorithm) : child;
    children.push(placed);
    const collect = node => {
      groupOf.set(node.id, child.id);
      for (const c of node.children || []) collect(c);
    };
    collect(child);
  }
  const pairs = new Map();
  for (const e of allEdges) {
    const src = groupOf.get(e.sources[0]), dst = groupOf.get(e.targets[0]);
    if (!src || !dst || src === dst) continue;
    const key = src + '|' + dst;
    if (!pairs.has(key)) pairs.set(key, {id: 'projected-' + pairs.size, sources:[src], targets:[dst], layoutOptions:{...e.layoutOptions}});
    else {
      const hint = pairs.get(key).layoutOptions;
      hint['elk.priority'] = String(Number(hint['elk.priority'] || 1) + Number(e.layoutOptions?.['elk.priority'] || 1));
    }
  }
  let edges = [...pairs.values()];
  if (algorithm === 'radial' && children.length) {
    // Radial requires a tree. Use an undirected spanning tree for positions;
    // back edges and self-loops remain in the rendered graph and metrics.
    const adjacent = new Map(children.map(c => [c.id, new Set()]));
    for (const e of edges) {
      adjacent.get(e.sources[0]).add(e.targets[0]);
      adjacent.get(e.targets[0]).add(e.sources[0]);
    }
    const root = children.find(c => c.id.endsWith('__self')) ||
      [...children].sort((a,b) => adjacent.get(b.id).size - adjacent.get(a.id).size)[0];
    const seen = new Set([root.id]), queue = [root.id];
    edges = [];
    for (let i = 0; i < queue.length; i++) {
      for (const dst of adjacent.get(queue[i])) {
        if (seen.has(dst)) continue;
        seen.add(dst); queue.push(dst);
        edges.push({id:'tree-' + edges.length, sources:[queue[i]], targets:[dst]});
      }
    }
    for (const child of children) {
      if (seen.has(child.id)) continue;
      seen.add(child.id);
      edges.push({id:'tree-' + edges.length, sources:[root.id], targets:[child.id]});
    }
  }
  const desiredLength = Math.max(300, ...children.map(c => Math.hypot(c.width, c.height) + 80));
  if (algorithm === 'stress') for (const edge of edges) {
    const weight = Number(edge.layoutOptions?.['elk.priority'] || 1);
    edge.layoutOptions = {...edge.layoutOptions,
      'elk.stress.desiredEdgeLength':String(desiredLength * (1 + 1 / Math.log2(2 + weight)))};
  }
  const result = await elk.layout({
    id:graph.id,
    layoutOptions:{...graph.layoutOptions, 'elk.hierarchyHandling':'SEPARATE_CHILDREN',
      'elk.stress.desiredEdgeLength':String(desiredLength)},
    children:children.map(c => ({id:c.id, width:c.width, height:c.height})), edges,
  });
  const originals = new Map(children.map(c => [c.id, c]));
  result.children = (result.children || []).map(c => ({...originals.get(c.id), x:c.x, y:c.y}));
  // Some tree providers return bounds for node centers rather than the full
  // restored compound sizes. Always enclose the actual ownership regions.
  if (result.children.length) {
    const minX = Math.min(...result.children.map(c => c.x));
    const minY = Math.min(...result.children.map(c => c.y));
    const maxX = Math.max(...result.children.map(c => c.x + c.width));
    const maxY = Math.max(...result.children.map(c => c.y + c.height));
    for (const child of result.children) { child.x += 24 - minX; child.y += 24 - minY; }
    result.width = maxX - minX + 48;
    result.height = maxY - minY + 48;
  }
  result.edges = [];
  return result;
}

// Returns node positions, routed edges and bounds in root coordinates.
async function layout(_elk, nodes, links, ownerOf, opts) {
  if (!_elk) throw new Error('elkjs not loaded');
  const algorithm = opts.algorithm || 'layered';
  if (!(await availableAlgorithms(_elk)).has(algorithm)) {
    throw new Error(algorithm + ' is not included in the vendored ELK bundle');
  }
  const direction = opts.direction || 'DOWN';
  const nodeMap = new Map(nodes.map(n => [n.id, n]));
  const childrenOf = new Map();
  for (const [c, p] of ownerOf) {
    if (!childrenOf.has(p)) childrenOf.set(p, []);
    childrenOf.get(p).push(c);
  }
  const isRoot = (id) => id === '(root)';
  // Every compound gets a self-pill (id + '__self'), a node inside it that
  // stands for the owner atom itself. The atom's edges, self-loops included,
  // end on the pill instead of the compound's border.
  const selfSuffix = '__self';
  const compoundIds = new Set();
  for (const n of nodes) if ((childrenOf.get(n.id) || []).length > 0) compoundIds.add(n.id);
  const compoundsNeedingSelf = compoundIds;
  const selfIdFor = (compoundId) => compoundId + selfSuffix;
  const elkEnd = (id) => compoundsNeedingSelf.has(id) ? selfIdFor(id) : id;
  const sp = {
    pad: 24,
    nodeNode: opts.nodesep,
    layer: Math.round(opts.ranksep * 0.35),
    edgeLayer: 16,
    edgeNode: 16,
    // edgeEdge also sets the gap between labels that share a row. Much below
    // 20 the gap is close to a word space, and neighbouring labels read as
    // one phrase.
    edgeEdge: 20,
    edgeEdgeLayer: 10,
  };
  // With INCLUDE_CHILDREN, ELK reads spacing and crossing-minimisation
  // options from each nested graph, not from the root, so the root and
  // every compound get the same set.
  const nestedOptions = {
    ...(algorithm === 'layered' ? {} : {'elk.algorithm': algorithm}),
    ...(usesDirection(algorithm) ? {'elk.direction': direction} : {}),
    'elk.randomSeed': '1',
    'elk.padding': `[top=${sp.pad},left=${sp.pad},bottom=${sp.pad},right=${sp.pad}]`,
    // nodeNode: between nodes in one layer (horizontal in TB).
    // nodeNodeBetweenLayers: between layers (vertical in TB). Labels have
    // their own slots, so these only set breathing room.
    'elk.spacing.nodeNode': String(sp.nodeNode),
    'elk.layered.spacing.nodeNodeBetweenLayers': String(sp.layer),
    'elk.layered.spacing.edgeNodeBetweenLayers': String(sp.edgeLayer),
    'elk.layered.spacing.edgeEdgeBetweenLayers': String(sp.edgeEdgeLayer),
    'elk.spacing.edgeNode': String(sp.edgeNode),
    'elk.spacing.edgeEdge': String(sp.edgeEdge),
    'elk.spacing.edgeLabel': '8',
    'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
    // Crossing-minimisation passes (ELK's default is 7). 100 removed a
    // crossing on every seed tried, for about three times the layout time,
    // which _elkLayoutMemo limits to graph changes.
    'elk.layered.thoroughness': '100',
    'elk.edgeRouting': 'ORTHOGONAL',
  };
  const buildElkNode = (n) => {
    const kids = childrenOf.get(n.id) || [];
    const en = {
      id: n.id,
      width: isRoot(n.id) ? 90 : opts.blockW,
      height: isRoot(n.id) ? 30 : opts.blockHFor(n),
    };
    if (kids.length) {
      en.children = kids.map(k => buildElkNode(nodeMap.get(k)));
      if (compoundsNeedingSelf.has(n.id)) {
        en.children.push({
          id: selfIdFor(n.id),
          width: opts.blockW,
          height: opts.blockHFor(n),
        });
      }
      en.layoutOptions = { ...nestedOptions };
    }
    return en;
  };
  const topLevel = nodes.filter(n => !ownerOf.has(n.id));
  const elkGraph = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': algorithm,
      ...(usesDirection(algorithm) ? {'elk.direction': direction} : {}),
      'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
      // Edge sections come back relative to the endpoints' lowest common
      // ancestor by default, not to the node the edge is declared in.
      // ROOT makes every edge root-relative, matching walkEdges below.
      'elk.json.edgeCoords': 'ROOT',
      ...nestedOptions,
    },
    children: topLevel.map(buildElkNode),
    // Labels go to ELK at their drawn size, so the layout places them clear
    // of nodes and other labels. ELK routes self-loops too.
    edges: links.map(({ e, rev }) => {
      // Obs edges carry an obs name instead of a message set; one directed
      // label, same placement options as message labels so ELK finds a slot
      // along the orthogonal route.
      if (e.kind === 'obs') {
        const id = edgeIdFor(e);
        const text = e.obs + (e.count > 1 ? ` ×${e.count}` : '');
        return {
          id,
          sources: [elkEnd(e.src)],
          targets: [elkEnd(e.dst)],
          labels: [{
            id: id + '__label',
            text, width: edgeLabelWidth(text), height: 16,
            layoutOptions: { 'elk.edgeLabels.placement': 'CENTER', 'elk.edgeLabels.inline': 'true' },
          }],
          layoutOptions: ['layered', 'stress'].includes(algorithm) ? {
            'elk.priority': '1',
          } : {},
        };
      }
      // ELK ignores these options on the graph, so they sit on each label.
      // CENTER gives the label its own slot mid-edge, and inline puts it on
      // the line rather than beside it. Two-way labels reserve room for a
      // direction arrow.
      const label = (d, i) => {
        const text = (rev ? '▼ ' : '') + edgeLabelText(d);
        return {
          id: e.src + '__' + e.dst + '__label' + i, text,
          width: edgeLabelWidth(text), height: 16,
          layoutOptions: { 'elk.edgeLabels.placement': 'CENTER', 'elk.edgeLabels.inline': 'true' },
        };
      };
      return {
        id: edgeIdFor(e),
        sources: [elkEnd(e.src)],
        targets: [elkEnd(e.dst)],
        labels: (rev ? [e, rev] : [e]).map(label),
        // This ELK build calls the hint elk.priority, not elk.edge.priority.
        // Shape-only graphs currently have no message edges; if supplied,
        // arm/message multiplicity provides their structural weight.
        layoutOptions: ['layered', 'stress'].includes(algorithm) ? {
          'elk.priority': String(Math.max(1, e.total + (rev?.total || 0) || e.msgs.size + (rev?.msgs.size || 0))),
          ...(algorithm === 'layered' ? {
            'elk.layered.priority.shortness': String(Math.max(1, e.total + (rev?.total || 0))),
          } : {}),
        } : {},
      };
    }),
  };
  // Headless tuning hook for tools/layout-metrics.py sweeps. With ?sync=1,
  // elkopt=key:value,key:value overrides options on the root and on every
  // compound (see nestedOptions).
  if (opts.overrides) {
    const overrides = opts.overrides.split(',').filter(Boolean);
    const apply = (en) => {
      if (en !== elkGraph && !en.children) return;
      en.layoutOptions = en.layoutOptions || {};
      for (const kv of overrides) {
        const i = kv.indexOf(':');
        if (i > 0) en.layoutOptions[kv.slice(0, i)] = kv.slice(i + 1);
      }
      for (const c of (en.children || [])) apply(c);
    };
    if (overrides.length) apply(elkGraph);
  }
  const _elkLayoutMemo = layoutMemo.get(_elk);
  const layoutKey = JSON.stringify(elkGraph);
  if (_elkLayoutMemo && _elkLayoutMemo.key === layoutKey) return _elkLayoutMemo.value;
  const result = algorithm === 'layered' ? await _elk.layout(elkGraph)
    : await layoutGroups(_elk, elkGraph, elkGraph.edges, algorithm);
  const pos = new Map();
  const parentOf = new Map();  // childId → parentId (headless geometry dump)
  const walkNodes = (node, ox, oy, parentId) => {
    const ax = ox + (node.x || 0);
    const ay = oy + (node.y || 0);
    if (node.id !== 'root') {
      pos.set(node.id, {
        x: ax, y: ay, w: node.width, h: node.height,
        cx: ax + node.width / 2, cy: ay + node.height / 2,
      });
      if (parentId) parentOf.set(node.id, parentId);
    }
    for (const c of (node.children || [])) {
      walkNodes(c, ax, ay, node.id === 'root' ? null : node.id);
    }
  };
  walkNodes(result, 0, 0, null);
  const layoutEdges = [];
  const walkEdges = (node, ax, ay) => {
    for (const e of (node.edges || [])) {
      const pts = (e.sections || []).flatMap(s => {
        const raw = [s.startPoint, ...(s.bendPoints || []), s.endPoint];
        return raw.map(p => ({ x: p.x + ax, y: p.y + ay }));
      });
      const stripSelf = (id) => id.endsWith(selfSuffix) ? id.slice(0, -selfSuffix.length) : id;
      layoutEdges.push({
        id: e.id,
        src: stripSelf(e.sources[0]), dst: stripSelf(e.targets[0]),
        elkSrc: e.sources[0], elkDst: e.targets[0],
        points: pts,
        // Primary direction first, then reverse, as sent to ELK.
        labels: (e.labels || []).map(lab => lab.x != null
          ? { x: lab.x + ax, y: lab.y + ay, w: lab.width }
          : null),
      });
    }
    for (const c of (node.children || [])) {
      walkEdges(c, ax + (c.x || 0), ay + (c.y || 0));
    }
  };
  walkEdges(result, 0, 0);
  if (algorithm !== 'layered') {
    const borderPoint = (a, b) => {
      const dx = b.cx - a.cx, dy = b.cy - a.cy;
      const t = 1 / Math.max(Math.abs(dx) / (a.w / 2), Math.abs(dy) / (a.h / 2), 1);
      return {x:a.cx + dx * t, y:a.cy + dy * t};
    };
    for (const e of elkGraph.edges) {
      const a = pos.get(e.sources[0]), b = pos.get(e.targets[0]);
      const self = e.sources[0] === e.targets[0];
      const points = self ? [
        {x:a.x + a.w, y:a.cy}, {x:a.x + a.w + 36, y:a.cy},
        {x:a.x + a.w + 36, y:a.y - 28}, {x:a.cx, y:a.y - 28}, {x:a.cx, y:a.y},
      ] : [borderPoint(a,b), borderPoint(b,a)];
      layoutEdges.push({id:e.id, src:e.sources[0].replace(/__self$/, ''), dst:e.targets[0].replace(/__self$/, ''),
        elkSrc:e.sources[0], elkDst:e.targets[0], points, labels:[]});
    }
  }
  // Content bounds cover node rects and edge points. ELK's width and height
  // start at (0,0) and ignore edges routed outside the root frame, whose
  // bend points can be negative.
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of pos.values()) {
    if (p.x < minX) minX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.x + p.w > maxX) maxX = p.x + p.w;
    if (p.y + p.h > maxY) maxY = p.y + p.h;
  }
  for (const e of layoutEdges) {
    for (const pt of e.points) {
      if (pt.x < minX) minX = pt.x;
      if (pt.y < minY) minY = pt.y;
      if (pt.x > maxX) maxX = pt.x;
      if (pt.y > maxY) maxY = pt.y;
    }
  }
  if (!isFinite(minX)) { minX = 0; minY = 0; maxX = result.width || 800; maxY = result.height || 400; }
  const value = { pos, layoutEdges, parentOf, minX, minY, maxX, maxY };
  layoutMemo.set(_elk, { key: layoutKey, value });
  return value;
}


function prettifyMsg(raw) {
  if (!raw) return '?';
  // `self.` prefix appears on self-directed fanout emits; strip before
  // matching so the cxc_emit_/cxc_stream_ regexes below don't miss.
  const bare = raw.startsWith('self.') ? raw.slice(5) : raw;
  const streamMatch = bare.match(/^cxc_stream_(.+?)_from_/);
  if (streamMatch) return streamMatch[1];
  const emitMatch = bare.match(/^cxc_emit_(.+)$/);
  if (emitMatch) return emitMatch[1];
  return bare.split('.').pop();
}

// Auto-container names appear in three flavors:
//   _Cxc_ListOfCard   → List of Card
//   _Cxc_MapOfStringInt → Map of (String, Int)
//   _Cxc_SeededPrng   → SeededPrng
function prettifyContainerName(cls) {
  let m = cls.match(/^_Cxc_ListOf(.+)$/);
  if (m) return 'List of ' + m[1].replace(/^_Cxc_/, '');
  m = cls.match(/^_Cxc_SetOf(.+)$/);
  if (m) return 'Set of ' + m[1].replace(/^_Cxc_/, '');
  m = cls.match(/^_Cxc_MapOf([A-Z][a-z]+)([A-Z].+)$/);
  if (m) return `Map of (${m[1]}, ${m[2]})`;
  return cls.replace(/^_Cxc_/, '');
}

// Share class/name resolution with the viewer's heat, phase and annotation paths.
function classResolver(shape, events = []) {
  const userNames = new Map();
  for (const e of events) {
    if (e.kind !== 'send' && e.kind !== 'send_sync') continue;
    const dot = (e.msg || '').indexOf('.');
    if (dot <= 0 || !e.target_cls || userNames.has(e.target_cls)) continue;
    const name = e.msg.slice(0, dot);
    if (!name.startsWith('cxc_')) userNames.set(e.target_cls, name);
  }
  // Fallback: for any trace class not pinned by a send, consult the sidecar's
  // declared atom names. Mangling patterns in use (compiler/src/cxc/lower/
  // emit_names.clj): `Cxc<Name>` for reserved names, `_Cxc_<Name>` for
  // prelude types, `<mod>_<Name>` and `<user>_<mod>_<Name>` for modules.
  // A passive atom (one that never receives a message) emits no send, so
  // without this fallback its trace node is unreachable and its obs edge
  // is silently dropped by `topology()` (round-3 F3 blocker).
  const shapeAtomNames = new Set((shape?.atoms || []).map(a => a.name));
  // Primary: sidecar now carries the emitted ObjC class name on each atom
  // entry (`:class`, written by `cxc.shape-sidecar/atom-class-name`), so
  // the viewer can resolve a trace class via exact string lookup instead
  // of guessing from underscore structure. Round-4+5 codex surfaced that
  // suffix heuristics cannot disambiguate module paths from atom names
  // whose own names contain underscores. Fall back to the suffix walk for
  // sidecars written before the class field was added.
  const byClass = new Map();
  for (const a of (shape?.atoms || [])) {
    if (a.class) byClass.set(a.class, a.name);
  }
  const tryStripToAtom = (cls) => {
    const direct = byClass.get(cls);
    if (direct) return direct;
    const bare = cls.replace(/^_?Cxc_?/, '');
    if (shapeAtomNames.has(bare)) return bare;
    const parts = cls.split('_');
    for (let i = 0; i < parts.length; i++) {
      const candidate = parts.slice(i).join('_');
      if (shapeAtomNames.has(candidate)) return candidate;
    }
    return null;
  };
  const displayLabel = cls => {
    if (cls === 'CxcMain') return (shape && shape.main_atom) || 'main';
    if (userNames.has(cls)) return userNames.get(cls);
    return tryStripToAtom(cls) || String(cls || '').replace(/^Cxc/, '');
  };
  const atomShapeForClass = cls => {
    if (!shape || !shape.atoms || !cls) return null;
    // Class-first: byClass is the authoritative mangled→name map from
    // the sidecar's `:class` field (round-6 landing). Without this
    // precedence, a sibling atom whose user-visible name literally
    // equals another atom's mangled class string shadows the correct
    // mapping and misbinds the trace node (round-7 codex raw-name-
    // shadow witness: two atoms, one in `Metrics.Worker` named `Stats`
    // → class `Metrics_Worker_Stats`, another in `other` named
    // `Metrics_Worker_Stats` → class `other_Metrics_Worker_Stats`).
    // Fall through to raw-name / displayLabel match only when byClass
    // lacks an entry (legacy sidecars without `:class`).
    const nameFromClass = byClass.get(cls);
    if (nameFromClass) {
      const hit = shape.atoms.find(a => a.name === nameFromClass);
      if (hit) return hit;
    }
    return shape.atoms.find(a => a.name === cls)
      || shape.atoms.find(a => a.name === displayLabel(cls)) || null;
  };
  // User-name lookup, exposed for callers that know they hold a sidecar
  // atom name (e.g. annotation targets with :atom or :from_atom). Keeps
  // the class-first `atomShapeForClass` from stealing a name match it
  // shouldn't own.
  const atomByName = nm => (shape && shape.atoms && nm)
    ? shape.atoms.find(a => a.name === nm) || null
    : null;
  return { displayLabel, atomShapeForClass, atomByName };
}

// Shared edge-id encoder. Must stay in lock-step with `linkOf` key lookup.
// JSON encodes the full tuple so an atom or obs name containing literal
// `__msg__`/`__obs__` substrings (or any other delimiter trick) cannot
// forge another edge's id. Underscores are legal identifier chars in
// Corvid (compiler/src/cxc/lex.clj), so a delimiter-based encoder is not
// collision-safe.
function edgeIdFor(e) {
  return e.kind === 'obs'
    ? JSON.stringify(['obs', e.src, e.dst, e.obs])
    : JSON.stringify(['msg', e.src, e.dst]);
}

// Cross-atom observation reads from the sidecar. One edge per (reader,target,
// obs) tuple so each landed label reads as a distinct obs call rather than a
// collapsed "+N" summary (which is how message edges collapse per msg name).
// Every obs_calls row has reader ≠ target (self-reads live in obs_self_calls
// and never render as edges), so `kind: 'obs'` edges are always `obs-external`.
//
// Endpoint names come from the sidecar, which carries the Corvid atom name
// (`main`, `Lane`). The trace path keys nodes by mangled C class names
// (`CxcMain`). `nameToNodeId` maps atom-name → trace-node-id when available,
// so an obs edge from `main` reaches the `CxcMain` node topology actually kept.
function buildObsEdges(_shape, nameToNodeId) {
  const out = [];
  const resolve = nameToNodeId || ((n) => n);
  for (const c of (_shape?.obs_calls || [])) {
    if (!c?.reader || !c?.target || !c?.obs) continue;
    out.push({ src: resolve(c.reader), dst: resolve(c.target), obs: c.obs,
               count: c.call_count || 1, kind: 'obs', classKey: 'obs-external',
               msgs: new Map(), total: 0, sync: 0 });
  }
  return out;
}

// Build atom-name → node-id lookup from the shape plus (optionally) the
// trace-mode node map. In shape-only mode nodes are keyed by atom name
// already, so the identity map suffices. In trace mode the node id is a
// trace-event target_cls — `Cxc`-prefixed for reserved names, `_Cxc_`
// prefixed for prelude types, `<module>_<Name>` for cross-module atoms
// (compiler/src/cxc/lower/emit_names.clj + compiler/src/cxc/resolve.clj).
// Enumerating prefix patterns misses forms. Use the real inverse: ask
// `atomShapeForClass` what shape atom each trace node belongs to, then
// map that shape atom's `name` back to the trace node id.
function obsNameResolver(_shape, traceNodeIds, atomShapeForClass) {
  if (!_shape?.atoms) return (n) => n;
  const idByAtomName = new Map();
  for (const a of _shape.atoms) idByAtomName.set(a.name, a.name);
  if (traceNodeIds && atomShapeForClass) {
    for (const nodeId of traceNodeIds) {
      const s = atomShapeForClass(nodeId);
      if (s && s.name) idByAtomName.set(s.name, nodeId);
    }
  }
  return (n) => idByAtomName.get(n) || n;
}

function buildGraph(_shape, _traceLoaded, filtered, displayLabel, prettifyMsg) {
  // Collect observed classes here; renderShape restricts them to declared
  // atoms when a shape is available. Without one, keep the full trace graph.
  // Local resolver gives `obsNameResolver` the full atom-name → node-id inverse
  // without needing callers to thread one in.
  const atomShapeForClass = classResolver(_shape, filtered || []).atomShapeForClass;

  const nodes = new Map();
  const edges = new Map();
  if (_shape && !_traceLoaded) {
    return {nodes:_shape.atoms.map(a => ({id:a.name, label:a.name, cls:a.name, shape:a, size:0})),
            edges: buildObsEdges(_shape, obsNameResolver(_shape, null, atomShapeForClass))};
  }
  for (const e of filtered) {
    const id = e.target_cls;
    if (!nodes.has(id)) {
      nodes.set(id, {id, label: displayLabel(e.target_cls) || id, cls: e.target_cls, size: 0});
    }
    nodes.get(id).size++;
  }
  for (const e of filtered) {
    if (e.kind !== 'send' && e.kind !== 'send_sync') continue;
    const src = e._inferredSenderCls || '(root)';
    const dst = e.target_cls;
    if (!nodes.has(src)) {
      nodes.set(src, {id: src, label: src === '(root)' ? 'init' : (displayLabel(src) || src), cls: src === '(root)' ? 'root' : src, size: 0});
    }
    const key = src + '|' + dst;
    if (!edges.has(key)) edges.set(key, {src, dst, msgs: new Map(), total: 0, sync: 0});
    const ed = edges.get(key);
    const msgLabel = prettifyMsg(e.msg);
    ed.msgs.set(msgLabel, (ed.msgs.get(msgLabel) || 0) + 1);
    ed.total++;
    if (e.kind === 'send_sync') ed.sync++;
  }
  const traceNodeIds = new Set([...nodes.keys()]);
  return {nodes: [...nodes.values()],
          edges: [...edges.values(),
                  ...buildObsEdges(_shape, obsNameResolver(_shape, traceNodeIds, atomShapeForClass))]};
}



function topology(nodes, edges, _shape, atomShapeForClass, showInit = false) {
  // H2-15: topology is a projection. Copy even root/trace-only nodes so
  // enriching the result never changes the caller's graph node objects.
  nodes = nodes.map(n => ({ ...n }));
  if (!showInit) {
    nodes = nodes.filter(n => n.id !== '(root)');
    edges = edges.filter(e => e.src !== '(root)' && e.dst !== '(root)');
  }
  // When the compiler shape sidecar is loaded, restrict blocks to atoms
  // the source declares (user atoms + main), and enrich each node with
  // its shape entry so we can draw pills and expand a phase graph. Any
  // trace-observed target class without a shape entry (auto containers,
  // synthesized fan-in atoms) is dropped from the topology; its edges
  // are dropped too so we don't leave dangling arrows to nowhere.
  if (_shape) {
    const kept = new Map();
    for (const n of nodes) {
      if (n.id === '(root)') { kept.set(n.id, n); continue; }
      // Prefer the shape buildGraph already attached (shape-only path sets
      // cls:a.name + shape:a inline; see :575). Falling through to
      // atomShapeForClass in that case runs the class-first byClass lookup
      // on a user-visible name — which misbinds when another atom's
      // mangled class string happens to equal this name (round-8 Opus
      // raw-name-shadow shape-only witness). Trace-mode nodes carry no
      // pre-attached shape so still resolve via the class-first path.
      const s = n.shape || atomShapeForClass(n.cls);
      if (s) kept.set(n.id, { ...n, shape: s, label: s.name }); // H2-15
    }
    nodes = [...kept.values()];
    const keepId = (id) => kept.has(id);
    edges = edges.filter(e => keepId(e.src) && keepId(e.dst));
  }
  // Compute ownership: for each atom-typed field NOT in the ctor param
  // list, the owner-atom class owns the field-type class. Used below to
  // build a compound graph so owners literally contain their children.
  const nodeIds = new Set(nodes.map(n => n.id));
  const clsToId = new Map();
  for (const n of nodes) {
    if (n.shape) clsToId.set(n.shape.name, n.id);
    clsToId.set(n.id, n.id);
  }
  const stripContainer = (t) => {
    let s = String(t || '');
    s = s.replace(/^Map of \([^,]+,\s*/, '').replace(/\)$/, '');
    s = s.replace(/^(List|Set) of /, '');
    s = s.replace(/[!?]$/, '');
    return s.trim();
  };
  // ownerOf[childId] = parentId. First owner wins for shared classes
  // (e.g. main.p0:Player and main.p1:Player both parent Player once).
  const ownerOf = new Map();
  // H2-16: accept ownership in node/field order, retaining the first owner.
  // Before adding child -> owner, walk the accepted owner chain. If it
  // reaches child (including self-ownership), drop this back-reference and
  // record it for layout-debug. Each accepted prefix is a forest; validation
  // happens here once, before any ELK consumer builds a containment tree.
  const droppedOwnership = [];
  for (const n of nodes) {
    if (!n.shape) continue;
    const ctorNames = new Set((n.shape.ctor_params || []).map(p => p.name));
    for (const f of (n.shape.fields || [])) {
      if (ctorNames.has(f.name)) continue;
      const childCls = stripContainer(f.type);
      const childId = clsToId.get(childCls);
      if (!childId || !nodeIds.has(childId)) continue;
      if (ownerOf.has(childId)) continue;
      let owner = n.id;
      const cycle = [childId, owner];
      while (owner !== childId && ownerOf.has(owner)) {
        owner = ownerOf.get(owner);
        cycle.push(owner);
      }
      if (owner === childId) {
        droppedOwnership.push({child: childId, owner: n.id, field: f.name, cycle});
        continue;
      }
      ownerOf.set(childId, n.id);
    }
  }
  // One link per atom pair, keyed by its primary direction (the ELK edge's
  // direction).
  const links = pairEdges(edges);
  return { nodes, edges, ownerOf, links, droppedOwnership }; // H2-16
}

// SVG painting depends only on its graph, layout and context;
// navigation and heatmap hooks are supplied by the viewer.
function renderSvg(nodes, edges, links, ownerOf, elkResult, context = {}) {
  const noop = () => {};
  const {
    shape: _shape = null, selectedNode = null, traceLoaded: _traceLoaded = false,
    heatOn = false, idPrefix = '', selectNode = noop,
    wireAnnotation: _wireAnnotation = noop, wireKeyboardClick: _wireKeyboardClick = noop,
    openEdge: showEdgeDetail = noop, highlight: highlightShape = noop,
    heatElement = noop, heatLabel = noop, heatAtomCount = () => 0, heatEdgeElement = noop,
    edgeTarget: _edgeAnnTarget = e => ({from_atom:e.src, to_atom:e.dst}),
    atomName: _atomAnnName = id => id,
  } = context;
  const { pos } = elkResult;
  const { layoutEdges } = elkResult;
  // Resolve `selectedNode` to a single node id once, so both the block
  // highlight at `:1095` and the owner-rect highlight at `:1286` light
  // the same single atom. `selectedNode` is a user-visible atom name in
  // every production path: CLJS seeds it from `shape-adapter/node-name`
  // (reads `shape.name`), the URL `?expand=…` carries a source name,
  // and the test harness mirrors that. Prefer name-match; id-match only
  // wins when no shape-only node name matches (defensive fallback for
  // call sites that happen to hand in a trace class). Round-10 Opus
  // raw-name-shadow witness confirmed id-first picks the WRONG atom
  // when the user clicks the sibling atom whose user name equals
  // another atom's mangled class.
  const selectedId = selectedNode
    ? ((nodes.find(n => n.shape && n.shape.name === selectedNode) || {}).id
       || (nodes.find(n => n.id === selectedNode) && selectedNode)
       || null)
    : null;
  // Keyed by the ELK edge id each layoutEdge carries. `edgeIdFor` namespaces
  // messages (`__msg__`) and obs reads (`__obs__`) so a message A→B and an
  // obs edge A→B.name can never collide.
  const linkOf = new Map(links.map(l => [edgeIdFor(l.e), l]));
  // Content box is the drawing plus a margin, so labels at the outer edge
  // don't touch the stage border. The camera frames this box.
  const marginX = 100;
  const marginY = 60;
  const contentX = elkResult.minX - marginX;
  const contentY = elkResult.minY - marginY;
  const W = (elkResult.maxX - elkResult.minX) + marginX * 2;
  const H = (elkResult.maxY - elkResult.minY) + marginY * 2;

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  // Fills the stage. applyShapeView() sets the viewBox from the camera once
  // the svg is mounted and has a size.
  svg.setAttribute('width', '100%');
  svg.setAttribute('height', '100%');
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  svg.dataset.shapeView = '1';
  svg.dataset.contentX = contentX;
  svg.dataset.contentY = contentY;
  svg.dataset.contentW = W;
  svg.dataset.contentH = H;
  svg.dataset.layout = elkResult.algorithm || context.algorithm || 'layered';

  const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
  defs.innerHTML = `
    <marker id="${idPrefix}shape-arrow" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto" markerUnits="userSpaceOnUse">
      <path d="M0,1 L11,6 L0,11 z" class="mk-ink"></path>
    </marker>
    <marker id="${idPrefix}shape-arrow-sync" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto" markerUnits="userSpaceOnUse">
      <path d="M0,1 L11,6 L0,11 z" class="mk-indigo"></path>
    </marker>
    <marker id="${idPrefix}shape-arrow-start" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
      <path d="M0,1 L11,6 L0,11 z" class="mk-ink"></path>
    </marker>
    <marker id="${idPrefix}shape-arrow-start-sync" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto-start-reverse" markerUnits="userSpaceOnUse">
      <path d="M0,1 L11,6 L0,11 z" class="mk-indigo"></path>
    </marker>
    <marker id="${idPrefix}shape-arrow-self" markerWidth="11" markerHeight="11" refX="10" refY="5.5" orient="auto" markerUnits="userSpaceOnUse">
      <path d="M0,1 L10,5.5 L0,10 z" class="mk-rust"></path>
    </marker>
    <marker id="${idPrefix}shape-arrow-obs" markerWidth="12" markerHeight="12" refX="11" refY="6" orient="auto" markerUnits="userSpaceOnUse">
      <path d="M0,1 L11,6 L0,11 z" class="mk-accent"></path>
    </marker>
  `;
  svg.appendChild(defs);

  // childrenOf and mainNodeId drive the box styling (isContainer, isOwner).
  // There are no layer bands, because a second kind of enclosing region
  // would compete with the ownership boxes as a grouping cue.
  const childrenOf = new Map();
  for (const [child, parent] of ownerOf) {
    if (!childrenOf.has(parent)) childrenOf.set(parent, []);
    childrenOf.get(parent).push(child);
  }
  const mainNodeId = (nodes.find(n => n.shape && n.shape.name === _shape?.main_atom) || {}).id;

  const edgeG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
  // Orthogonal route with small fixed-radius corners. The radius is capped
  // at half of each adjacent segment, so short jogs stay sharp and the path
  // stays within about r of ELK's route.
  // Obstacles for the fallback label search. Pills paint over edges, so a
  // label under one would be hidden. Containers are left out because their
  // box covers the whole subtree and would block every position inside it.
  const blockRects = [...pos.entries()]
    .filter(([id]) => id.endsWith('__self') || !childrenOf.has(id))
    .map(([, b]) => ({ x: b.x - 4, y: b.y - 4, w: b.w + 8, h: b.h + 8 }));
  // Labels placed so far. The fallback search rejects positions that overlap
  // them, and the headless dump reports them.
  const placedLabels = [];
  const tabRects = [];
  function rectsOverlap(a, b) {
    return !(a.x + a.w < b.x || b.x + b.w < a.x || a.y + a.h < b.y || b.y + b.h < a.y);
  }
  function pointInAnyBlock(x, y) {
    return blockRects.some(b => x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h);
  }
  function labelRectClears(cx, cy, w, h) {
    const rect = { x: cx - w/2, y: cy - h/2, w, h };
    // Reject if inside any block, or overlaps a previously-placed label.
    const corners = [
      [rect.x, rect.y], [rect.x + w, rect.y],
      [rect.x, rect.y + h], [rect.x + w, rect.y + h],
      [cx, cy],
    ];
    if (corners.some(([x, y]) => pointInAnyBlock(x, y))) return false;
    if (placedLabels.some(pl => rectsOverlap(rect, pl))) return false;
    return true;
  }
  function bestLabelAnchor(pts) {
    let best = { x: pts[0].x, y: pts[0].y, len: 0 };
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      const dx = b.x - a.x, dy = b.y - a.y;
      const len = Math.hypot(dx, dy);
      if (len > best.len) best = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, len };
    }
    return best;
  }
  const dirSummary = (d) => {
    const msgList = [...d.msgs.entries()].sort((a, b) => b[1] - a[1]);
    return `${d.src} → ${d.dst}\n${d.total} messages (${d.sync} sync)\n\n`
      + msgList.slice(0, 8).map(([m, c]) => `${m} × ${c}`).join('\n')
      + (msgList.length > 8 ? `\n… +${msgList.length - 8} more` : '');
  };
  for (const layoutEdge of layoutEdges) {
    const link = linkOf.get(layoutEdge.id);
    if (!link) continue;
    if (!layoutEdge.points || !layoutEdge.points.length) continue;
    const { e, rev } = link;
    // Observation edges render through the same orthogonal-routed path +
    // arrow-marker + ELK-placed label idiom as message edges, so the two
    // layers share one visual language. Styling branches via CSS class.
    if (e.kind === 'obs') {
      const pts = layoutEdge.points;
      const path = pointsToPath(pts);
      const classKey = e.classKey || 'obs-external';
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', path);
      p.setAttribute('class', 'shape-edge shape-obs-edge ' + classKey);
      p.setAttribute('marker-end', `url(#${idPrefix}shape-arrow-obs)`);
      p.dataset.src = e.src;
      p.dataset.dst = e.dst;
      p.dataset.obs = e.obs;
      const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
      title.textContent = `${e.src} reads ${e.dst}.${e.obs}` + (e.count > 1 ? ` (${e.count} call sites)` : '');
      p.appendChild(title);
      edgeG.appendChild(p);
      // Label via ELK's own anchor; fall back to the longest-segment midpoint,
      // then search obstacle-clear positions so parallel obs reads on the same
      // (src,dst) pair don't paint on top of each other in non-layered layouts.
      const base = e.obs + (e.count > 1 ? ` ×${e.count}` : '');
      const textW = edgeLabelWidth(base);
      const textH = 16;
      const lab = layoutEdge.labels[0];
      let anchor = lab && lab.x != null
        ? { x: lab.x + lab.w / 2, y: lab.y + 9 }
        : bestLabelAnchor(pts);
      if (!(lab && lab.x != null)) {
        const candidates = [anchor];
        for (let k = 1; k < pts.length; k++) {
          const a = pts[k - 1], b = pts[k];
          for (const t of [0.5, 0.25, 0.75, 0.4, 0.6]) {
            candidates.push({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
          }
        }
        // Expanding offsets: perpendicular jogs step past the 16px label height.
        // Non-layered algos collapse all same-pair obs to one polyline, so the
        // outer loop needs enough reach to seat 10+ parallel labels without
        // overlap. Falling through means EVERY tested slot was obstructed —
        // paint it at the ELK anchor anyway rather than invent a position
        // off-canvas, but mark it so later labels still try to avoid it.
        let placed = false;
        outer: for (const cand of candidates) {
          for (const dy of [0, -18, 18, -36, 36, -54, 54, -72, 72, -90, 90]) {
            if (labelRectClears(cand.x, cand.y + dy, textW, textH)) {
              anchor = { x: cand.x, y: cand.y + dy };
              placed = true;
              break outer;
            }
          }
        }
        if (!placed) {
          // Bounded horizontal spread with per-slot clearance check. Step is
          // `textW + 6` (full width plus a thin gap) so adjacent positions
          // don't overlap even for narrow labels; a half-width step packed
          // short labels tight enough that labels 0 and 4 collided (round-4
          // codex F5 "short labels regress at four reads" witness). Floor is
          // 24 so a 1-char label still spreads legibly. Cap stays modest to
          // keep the fallback close to the ELK anchor; the actual bounds-
          // contamination fix is on the push below (fallback labels are
          // marked `boundsSkip` so they clear subsequent labels but don't
          // widen content extents, round-4 F5 "spread contaminates narrow-
          // view framing" witness).
          const step = Math.max(textW + 6, 24);
          // Five magnitudes per side give 11 total slots (anchor + 10) so
          // the 8th and 9th short reads still find distinct positions
          // (round-5 codex F5.2 witness at eight ::a..::h on one edge).
          // Wide labels inherit the same magnitude multiplier, which can
          // push them past the camera-fit viewport at narrow stages;
          // `boundsSkip` on these fallback labels still prevents them
          // from shrinking the primary pill content. The trade-off is
          // explicit — pathological density clips labels before it
          // shrinks pills.
          const dxCap = step * 5;
          horz: for (let n = 1; n * step <= dxCap; n++) {
            for (const sign of [-1, 1]) {
              const dx = sign * n * step;
              for (const dy of [0, -18, 18, -36, 36]) {
                if (labelRectClears(candidates[0].x + dx, candidates[0].y + dy, textW, textH)) {
                  anchor = { x: candidates[0].x + dx, y: candidates[0].y + dy };
                  placed = true;
                  break horz;
                }
              }
            }
          }
          if (!placed) {
            anchor = { x: candidates[0].x, y: candidates[0].y };
          }
        }
      }
      // Fallback-placed obs labels are tracked for clearance (so following
      // labels still skirt them) but not for content bounds. Including a
      // ±(1.5·textW + 18) sidewinder in `extents` at `:1270` grows the
      // camera-fit content width through the CLJS adapter at
      // `shape_canvas_adapter.cljs:115` and `app.cljs:362`, shrinking every
      // on-screen pill at narrow viewport (round-4 codex F5 bounds witness).
      const labelFromFallback = !(lab && lab.x != null);
      placedLabels.push({ x: anchor.x - textW/2, y: anchor.y - 9, w: textW, h: textH, text: base, kind: 'obs', boundsSkip: labelFromFallback });
      const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      bg.setAttribute('x', anchor.x - textW / 2);
      bg.setAttribute('y', anchor.y - 9);
      bg.setAttribute('width', textW);
      bg.setAttribute('height', textH);
      bg.setAttribute('rx', 3);
      bg.setAttribute('class', 'shape-edge-label-bg shape-obs-edge-label-bg ' + classKey);
      edgeG.appendChild(bg);
      const t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      t.setAttribute('x', anchor.x);
      t.setAttribute('y', anchor.y - 1);
      t.setAttribute('class', 'shape-edge-label shape-obs-edge-label ' + classKey);
      t.setAttribute('text-anchor', 'middle');
      t.textContent = base;
      edgeG.appendChild(t);
      continue;
    }
    const dirs = rev ? [e, rev] : [e];
    const pts = layoutEdge.points;
    // "sync only" means every message on the line, in both directions.
    const isSync = dirs.every(d => d.total > 0 && d.sync === d.total);
    const isSelfLoop = e.src === e.dst;
    const path = pointsToPath(pts);
    const edgeId = `edge-${e.src}--${e.dst}`.replace(/[^a-zA-Z0-9-]/g, '_');
    const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', path);
    p.setAttribute('class', 'shape-edge' + (isSelfLoop ? ' self' : isSync ? ' sync' : ''));
    p.setAttribute('marker-end', isSelfLoop ? `url(#${idPrefix}shape-arrow-self)` : isSync ? `url(#${idPrefix}shape-arrow-sync)` : `url(#${idPrefix}shape-arrow)`);
    if (rev) p.setAttribute('marker-start', isSync ? `url(#${idPrefix}shape-arrow-start-sync)` : `url(#${idPrefix}shape-arrow-start)`);
    p.dataset.src = e.src;
    p.dataset.dst = e.dst;
    p.dataset.edgeId = edgeId;
    // gap 30: retain v2's stroke tint alongside legacy directional labels.
    if (_traceLoaded) heatEdgeElement(p, e);
    const plusHint = dirs.some(d => d.msgs.size > 1)
      ? `\n\n"+N" on a label = N more message names in that direction` : '';
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    title.textContent = dirs.map(dirSummary).join('\n\n') + plusHint;
    p.appendChild(title);
    p.style.cursor = 'pointer';
    // Transparent fat hit-path underneath the visible stroke. At 1.1px the
    // visible stroke is too thin for a reliable hover target. The hit path
    // renders behind the visible one with the same `d`, a 14-unit transparent
    // stroke, and routes events to the same handlers.
    const pHit = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    pHit.setAttribute('d', path);
    pHit.setAttribute('class', 'shape-edge-hit');
    pHit.setAttribute('fill', 'none');
    pHit.setAttribute('stroke', 'transparent');
    pHit.setAttribute('stroke-width', '14');
    pHit.setAttribute('pointer-events', 'stroke');
    pHit.style.cursor = 'pointer';
    pHit.dataset.edgeId = edgeId;
    const edgeAnnotation = {kind:'edge', target:_edgeAnnTarget(e)};
    _wireAnnotation(pHit, edgeAnnotation, 'edge');
    pHit.addEventListener('click', () => showEdgeDetail(e, rev));
    _wireKeyboardClick(pHit, 'View messages from ' + e.src + ' to ' + e.dst + '. Shift+Enter to annotate.');
    edgeG.appendChild(pHit);
    edgeG.appendChild(p);
    const highlightPair = (on) => {
      const q = `[data-edge-id="${edgeId}"]`;
      svg.querySelectorAll(q).forEach(el => {
        if (on) el.classList.add('shape-edge-hover');
        else    el.classList.remove('shape-edge-hover');
      });
    };
    const onEnter = () => { highlightPair(true); highlightShape([e.src, e.dst]); };
    const onLeave = () => { highlightPair(false); highlightShape(null); };
    pHit.addEventListener('mouseenter', onEnter);
    pHit.addEventListener('mouseleave', onLeave);
    pHit.addEventListener('focus', onEnter);
    pHit.addEventListener('blur', onLeave);
    p.addEventListener('mouseenter', onEnter);
    p.addEventListener('mouseleave', onLeave);
    _wireAnnotation(p, edgeAnnotation, 'edge');
    p.addEventListener('click', () => showEdgeDetail(e, rev));

    dirs.forEach((d, i) => {
      // ELK positions the label. The polyline search below only runs if ELK
      // returned a label without a position.
      const base = edgeLabelText(d);
      const textW = edgeLabelWidth(rev ? '▼ ' + base : base);
      const textH = 16;
      const lab = layoutEdge.labels[i];
      let anchor = null;
      let found = false;
      if (lab) {
        anchor = { x: lab.x + lab.w / 2, y: lab.y + 9 };
        found = true;
      }
      const candidates = [];
      if (!found) {
        candidates.push(bestLabelAnchor(pts));
        for (let k = 1; k < pts.length; k++) {
          const a = pts[k - 1], b = pts[k];
          for (const t of [0.5, 0.25, 0.75, 0.15, 0.85]) {
            candidates.push({ x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t });
          }
        }
        anchor = candidates[0];
        for (const cand of candidates) {
          if (labelRectClears(cand.x, cand.y, textW, textH)) { anchor = cand; found = true; break; }
        }
      }
      if (!found) {
        outer: for (const cand of candidates) {
          for (const dy of [-12, 12, -24, 24, -36, 36]) {
            if (labelRectClears(cand.x, cand.y + dy, textW, textH)) {
              anchor = { x: cand.x, y: cand.y + dy }; break outer;
            }
          }
        }
      }
      let lbl = base;
      if (rev) {
        const g = travelGlyph(pts, anchor.x, anchor.y - 1);
        lbl = (i === 0 ? g : flipGlyph[g]) + ' ' + base;
      }
      placedLabels.push({ x: anchor.x - textW/2, y: anchor.y - 9, w: textW, h: textH, edgeId, text: lbl, kind: isSelfLoop ? 'self' : 'edge' });
      const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      bg.setAttribute('x', anchor.x - textW / 2);
      bg.setAttribute('y', anchor.y - 9);
      bg.setAttribute('width', textW);
      bg.setAttribute('height', textH);
      bg.setAttribute('rx', 3);
      bg.setAttribute('class', 'shape-edge-label-bg');
      bg.dataset.edgeId = edgeId;
      const bgTitle = document.createElementNS('http://www.w3.org/2000/svg', 'title');
      bgTitle.textContent = dirSummary(d);
      bg.appendChild(bgTitle);
      if (_traceLoaded) heatEdgeElement(bg, d);
      _wireAnnotation(bg, {kind:'edge', target:_edgeAnnTarget(d)}, 'edge-label');
      bg.addEventListener('click', () => showEdgeDetail(d));
      _wireKeyboardClick(bg, 'View messages from ' + _atomAnnName(d.src) + ' to ' + _atomAnnName(d.dst) + '. Shift+Enter to annotate.');
      bg.addEventListener('mouseenter', () => highlightPair(true));
      bg.addEventListener('mouseleave', () => highlightPair(false));
      edgeG.appendChild(bg);
      const t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      t.setAttribute('x', anchor.x);
      t.setAttribute('y', anchor.y - 1);
      t.setAttribute('class', 'shape-edge-label');
      t.setAttribute('text-anchor', 'middle');
      t.dataset.edgeId = edgeId;
      t.textContent = lbl;
      if (_traceLoaded) heatLabel(t, bg);
      edgeG.appendChild(t);
    });
  }

  // Container boxes sit below edges. Their border is annotatable, while the
  // pill inside handles atom navigation. Empty interiors remain pan surfaces.
  const containerG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
  svg.appendChild(containerG);
  svg.appendChild(edgeG);

  // Blocks. When shape is loaded, each block reads as a source card:
  //   name (bold) · <M phases, N transitions> · one row per container field.
  // Container fields render as pills ("hand: List of Card") inside the block
  // so the topology graph doesn't split them out as sibling atoms. Clicking
  // a block expands its phase graph in the detail pane.
  const blockG = document.createElementNS('http://www.w3.org/2000/svg', 'g');
  for (const n of nodes) {
    const p = pos.get(n.id);
    if (!p) continue;
    const isMain = n.cls === 'CxcMain' || (n.shape && n.shape.name === _shape?.main_atom);
    // Selection may be seeded either as the node id (from a click) or as the
    // atom name (from `?expand=…`). Match both so URL-seeded selection lights
    // up the halo without needing a click.
    const isSelected = selectedId === n.id;
    // Dim unrelated blocks whenever a phase graph is open so the reader
    // sees the topology → detail relationship at a glance.
    const dimForSelection = selectedNode && !isSelected && n.id !== '(root)';
    const isOwner = childrenOf.has(n.id) && n.id !== mainNodeId;
    // isContainer: owns children in the ELK-compound hierarchy.
    // Main is always a container (it wraps everything else).
    const isContainer = childrenOf.has(n.id) || isMain;
    // With a self-pill, the container is only a region (see containerG).
    const hasSelfPill = isContainer && pos.has(n.id + '__self');
    const clsAttr = 'shape-block' +
      (n.id === '(root)' ? ' root' : '') +
      (isMain ? ' main' : '') +
      (isOwner ? ' owner' : '') +
      (isContainer ? ' container' : '') +
      (isSelected ? ' selected' : '') +
      (dimForSelection ? ' dim-when-selected' : '');
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', p.x); rect.setAttribute('y', p.y);
    rect.setAttribute('width', p.w); rect.setAttribute('height', p.h);
    rect.setAttribute('rx', 5);
    rect.setAttribute('class', clsAttr);
    rect.dataset.nodeId = n.id;
    rect.dataset.nodeName = (n.shape && n.shape.name) || n.cls || n.id;
    // Selection halo — soft wash rect painted behind the block, plus the
    // block rect re-appended at the end so it paints above neighbors.
    if (isSelected) {
      const pad = 6;
      const glow = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      glow.setAttribute('x', p.x - pad); glow.setAttribute('y', p.y - pad);
      glow.setAttribute('width', p.w + pad*2); glow.setAttribute('height', p.h + pad*2);
      glow.setAttribute('rx', 10);
      glow.setAttribute('class', 'shape-block-halo-outer');
      (hasSelfPill ? containerG : blockG).appendChild(glow);
    }
    if (hasSelfPill) {
      // Keep empty container interiors available for panning; their border
      // remains an annotation target below the edge layer.
      rect.style.pointerEvents = 'stroke';
      _wireAnnotation(rect, {kind:'node', target:{atom:rect.dataset.nodeName}}, 'container', true);
      containerG.appendChild(rect);
      continue;
    }
    _wireAnnotation(rect, {kind:'node', target:{atom:rect.dataset.nodeName}}, 'node');
    rect.addEventListener('click', () => {
      selectNode(n);
    });
    rect.addEventListener('mouseenter', () => highlightShape(n.id));
    rect.addEventListener('mouseleave', () => highlightShape(null));
    _wireKeyboardClick(rect, 'View atom ' + rect.dataset.nodeName + '. Shift+Enter to annotate.');
    rect.innerHTML = `<title>${escapeHtml(n.shape ? n.shape.name : n.id)}\nshift-click or right-click to annotate${_traceLoaded && !heatOn ? '\n' + n.size + ' trace events' : ''}</title>`;
    if (!isContainer && n.id !== '(root)') heatElement(rect, heatAtomCount(n));
    blockG.appendChild(rect);

    // Title line — atom name. Container titles sit in a folder-style tab
    // above the container border; leaf titles keep their original centered
    // placement inside the pill.
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', isContainer ? p.x + 14 : p.cx);
    const hasFieldsBadge = !!n.shape?.fields.length && !isContainer;
    label.setAttribute('y', n.id === '(root)' ? p.y + p.h / 2 : isContainer ? p.y - 25 : hasFieldsBadge ? p.y + 20 : p.cy - 9);
    if (!isContainer) label.setAttribute('dominant-baseline', 'central');
    label.setAttribute('class', 'shape-block-label' + (isContainer ? ' container' : '') + (isMain ? ' main' : ''));
    label.textContent = n.id === '(root)' ? 'init' : n.label;
    if (!isContainer && n.id !== '(root)') heatLabel(label, rect);

    if (n.id === '(root)') {
      blockG.appendChild(label);
      continue;
    }

    // Subtitle — shape summary or bare event count
    const sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    sub.setAttribute('x', isContainer ? p.x + 14 : p.cx);
    sub.setAttribute('y', isContainer ? p.y - 25 : hasFieldsBadge ? p.y + 38 : p.cy + 9);
    sub.setAttribute('class', 'shape-block-sub' + (isContainer ? ' container' : '') + (isMain ? ' main' : ''));
    if (n.shape) {
      const nPhases = n.shape.phases.length;
      const nTxns = n.shape.transitions.length + n.shape.self_transitions.length;
      sub.textContent = `${nPhases} phases · ${nTxns} arms`;
    } else {
      sub.textContent = n.size + ' events';
    }
    if (!isContainer) heatLabel(sub, rect);

    if (isContainer) {
      // Overshoot the actual char widths so the tab has real padding
      // instead of touching the label glyphs. 16px bold ~= 10px/char,
      // 12px regular ~= 7px/char, plus a 20px gutter between the two
      // runs and 14px padding at each end.
      const labelW = label.textContent.length * 10;
      const subW = sub.textContent.length * 7;
      const tabW = Math.max(200, Math.ceil(labelW + subW + 48));
      const tab = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      // Push the tab 12px above the container border (was 4) so the two
      // rects don't visually merge into one thick line.
      tab.setAttribute('x', p.x); tab.setAttribute('y', p.y - 38);
      tab.setAttribute('width', tabW); tab.setAttribute('height', 26);
      tabRects.push({x:p.x, y:p.y - 38, w:tabW, h:26});
      tab.setAttribute('rx', 5);
      // The tab branch only fires for main atoms with no children; every
      // compound hits the self-pill continue path above.
      tab.setAttribute('class', 'shape-container-tab container main' + (isSelected ? ' selected' : ''));
      heatElement(tab, heatAtomCount(n));
      heatLabel(label, tab); heatLabel(sub, tab);
      _wireAnnotation(tab, {kind:'node', target:{atom:rect.dataset.nodeName}}, 'container-tab');
      // Tab shares the container's click / hover contract: clicking opens
      // its phase graph, hovering lights up the container + descendants.
      tab.addEventListener('mouseenter', () => highlightShape(n.id));
      tab.addEventListener('mouseleave', () => highlightShape(null));
      tab.addEventListener('click', () => {
        selectNode(n);
      });
      _wireKeyboardClick(tab, 'View atom ' + n.id);
      sub.setAttribute('x', p.x + tabW - 14);
      sub.setAttribute('text-anchor', 'end');
      blockG.appendChild(tab);
    }
    blockG.appendChild(label);
    blockG.appendChild(sub);

    // Compact fields badge — one strip inside the block. Skipped for
    // ELK containers because the strip would obstruct the child pills
    // that sit inside the container's own bounds.
    if (n.shape && !isContainer) {
      const containerFields = n.shape.fields.filter(f => f.container_kind);
      const nContainers = containerFields.length;
      const nAtomLinks = n.shape.fields.filter(f => nodes.some(nn => nn.shape && nn.shape.name === f.type)).length;
      const nOther = n.shape.fields.length - nContainers - nAtomLinks;
      if (n.shape.fields.length > 0) {
        const badgeW = p.w - 20;
        const badgeX = p.x + 10;
        const badgeY = p.y + 52;
        const badge = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        badge.setAttribute('x', badgeX); badge.setAttribute('y', badgeY);
        badge.setAttribute('width', badgeW); badge.setAttribute('height', 16);
        badge.setAttribute('rx', 3);
        badge.setAttribute('class', 'shape-block-badge');
        blockG.appendChild(badge);
        const bt = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        bt.setAttribute('x', badgeX + badgeW / 2);
        bt.setAttribute('y', badgeY + 8);
        bt.setAttribute('class', 'shape-block-badge-label');
        // Compact form: "N fields (M refs)". Refs = containers + direct
        // atom-typed fields — the hover-highlightable subset. Tooltip
        // spells out the breakdown; the visual stays block-wide-safe.
        const nFields = n.shape.fields.length;
        const nRefs = nContainers + nAtomLinks;
        bt.textContent = nRefs > 0
          ? `${nFields} field${nFields > 1 ? 's' : ''} · ${nRefs} ref${nRefs > 1 ? 's' : ''}`
          : `${nFields} field${nFields > 1 ? 's' : ''}`;
        const tip = document.createElementNS('http://www.w3.org/2000/svg', 'title');
        tip.textContent = [
          nContainers ? `${nContainers} container field${nContainers > 1 ? 's' : ''}` : null,
          nAtomLinks  ? `${nAtomLinks} atom-typed field${nAtomLinks > 1 ? 's' : ''}` : null,
          nOther      ? `${nOther} scalar field${nOther > 1 ? 's' : ''}` : null,
        ].filter(Boolean).join(' · ');
        badge.appendChild(tip);
        badge.setAttribute('role', 'img');
        badge.setAttribute('tabindex', '0');
        badge.setAttribute('aria-label', n.id + ': ' + tip.textContent);
        _wireAnnotation(badge, {kind:'node', target:{atom:rect.dataset.nodeName}}, 'fields', true);
        blockG.appendChild(bt);
        // Hover on badge highlights every atom this block references via
        // its List-of / Set-of / atom-typed fields.
        const linkedIds = new Set([n.id]);
        for (const f of n.shape.fields) {
          const inner = /^(?:List|Set) of (.+)$/.exec(f.type);
          const targetName = inner ? inner[1] : f.type;
          const target = nodes.find(nn => nn.shape && nn.shape.name === targetName);
          if (target) linkedIds.add(target.id);
        }
        if (linkedIds.size > 1) {
          badge.style.cursor = 'help';
          badge.addEventListener('mouseenter', () => highlightShape([...linkedIds]));
          badge.addEventListener('mouseleave', () => highlightShape(null));
          badge.addEventListener('focus', () => highlightShape([...linkedIds]));
          badge.addEventListener('blur', () => highlightShape(null));
        }
      }
    }
  }
  // Self-pills: each compound's own atom, drawn as a full atom card (name,
  // phase and arm counts) that handles clicks and hover for the atom.
  const nodeById = new Map(nodes.map(n => [n.id, n]));
  for (const [id, p] of pos.entries()) {
    if (!id.endsWith('__self')) continue;
    const parentId = id.slice(0, -'__self'.length);
    const parent = nodeById.get(parentId);
    if (!parent) continue;
    const isMain = parent.cls === 'CxcMain' || (parent.shape && parent.shape.name === _shape?.main_atom);
    const isSelected = selectedId === parentId;
    const dimForSelection = selectedNode && !isSelected;
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', p.x); rect.setAttribute('y', p.y);
    rect.setAttribute('width', p.w); rect.setAttribute('height', p.h);
    rect.setAttribute('rx', 5);
    rect.setAttribute('class', 'shape-block' +
      (isMain ? ' main' : '') +
      (isSelected ? ' selected' : '') +
      (dimForSelection ? ' dim-when-selected' : ''));
    rect.dataset.nodeId = parentId;
    rect.dataset.nodeName = (parent.shape && parent.shape.name) || parent.cls || parentId;
    _wireAnnotation(rect, {kind:'node', target:{atom:rect.dataset.nodeName}}, 'node');
    rect.addEventListener('click', () => {
      selectNode(parent);
    });
    rect.addEventListener('mouseenter', () => highlightShape(parentId));
    rect.addEventListener('mouseleave', () => highlightShape(null));
    _wireKeyboardClick(rect, 'View atom ' + rect.dataset.nodeName + '. Shift+Enter to annotate.');
    rect.innerHTML = `<title>${escapeHtml(parent.shape ? parent.shape.name : parentId)}${_traceLoaded && !heatOn ? '\n' + parent.size + ' trace events' : ''}</title>`;
    heatElement(rect, heatAtomCount(parent));
    g.appendChild(rect);
    const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    label.setAttribute('x', p.cx);
    label.setAttribute('y', p.cy - 9);
    label.setAttribute('class', 'shape-block-label' + (isMain ? ' on-pastel' : ''));
    label.textContent = parent.label;
    heatLabel(label, rect);
    g.appendChild(label);
    const sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    sub.setAttribute('x', p.cx);
    sub.setAttribute('y', p.cy + 9);
    sub.setAttribute('class', 'shape-block-sub' + (isMain ? ' on-pastel' : ''));
    if (parent.shape) {
      const nPhases = parent.shape.phases.length;
      const nTxns = parent.shape.transitions.length + parent.shape.self_transitions.length;
      sub.textContent = `${nPhases} phases · ${nTxns} arms`;
    } else {
      sub.textContent = parent.size + ' events';
    }
    heatLabel(sub, rect);
    g.appendChild(sub);
    blockG.appendChild(g);
  }
  svg.appendChild(blockG);
  // Include fallback edge labels, which alternative algorithms don't place.
  // Obs labels placed by the per-slot clearance search carry `boundsSkip`
  // because the fallback is allowed to pull labels past the natural edge
  // extent (round-4 codex F5 bounds); including those positions here would
  // grow the camera-fit frame and shrink every pill at narrow viewport.
  const extents = [...placedLabels.filter(l => !l.boundsSkip), ...tabRects];
  const minX = Math.min(elkResult.minX, ...extents.map(l => l.x));
  const minY = Math.min(elkResult.minY, ...extents.map(l => l.y));
  const maxX = Math.max(elkResult.maxX, ...extents.map(l => l.x + l.w));
  const maxY = Math.max(elkResult.maxY, ...extents.map(l => l.y + l.h));
  svg.dataset.contentX = minX - marginX;
  svg.dataset.contentY = minY - marginY;
  svg.dataset.contentW = maxX - minX + marginX * 2;
  svg.dataset.contentH = maxY - minY + marginY * 2;
  return { svg, placedLabels, childrenOf, bounds:{minX, minY, maxX, maxY} };
}

function metrics(layout, bounds = layout) {
  const segments = layout.layoutEdges.map(e => e.points.slice(1).map((b,i) => [e.points[i],b]));
  let edgeLength = 0, crossings = 0;
  for (const edge of segments) for (const [a,b] of edge) edgeLength += Math.hypot(b.x-a.x,b.y-a.y);
  const intersects = ([a,b],[c,d]) => {
    const dx = b.x-a.x, dy = b.y-a.y, ex = d.x-c.x, ey = d.y-c.y;
    const det = dx*ey-dy*ex;
    if (Math.abs(det) < 1e-8) return false;
    const t = ((c.x-a.x)*ey-(c.y-a.y)*ex)/det;
    const u = ((c.x-a.x)*dy-(c.y-a.y)*dx)/det;
    return t > 1e-6 && t < 1-1e-6 && u > 1e-6 && u < 1-1e-6;
  };
  // Count each intersecting edge pair once. Endpoint touches and collinear
  // overlaps are excluded; rounded SVG corners are approximated by segments.
  for (let i=0;i<segments.length;i++) for (let j=i+1;j<segments.length;j++) {
    if (segments[i].some(a => segments[j].some(b => intersects(a,b)))) crossings++;
  }
  return {width:bounds.maxX-bounds.minX, height:bounds.maxY-bounds.minY, crossings, edgeLength};
}

// gap 62: pinned vendored ELK worker facade, identical to legacy sync=1.
function enableSynchronousElk(elk) {
  const promised = elk && elk.worker, inline = promised && promised.worker;
  if (!inline?.dispatcher || typeof inline.dispatcher.saveDispatch !== 'function' || typeof promised.receive !== 'function')
    throw new Error('synchronous ELK mode is unavailable for this elkjs build');
  inline.onmessage = answer => promised.receive(promised, answer);
  inline.postMessage = message => inline.dispatcher.saveDispatch({data:message});
}

// H2-14: request is {shape: nullable JSON, events: ordered JSON array, elk,
// opts: layout options, context: callback bag, isCurrent: () -> boolean}.
// Callback argument contracts are specified in shape_canvas_adapter.cljs.
// Resolves to null when superseded, otherwise {layout: raw ELK workspaces,
// bounds: {minX,minY,maxX,maxY}, debug: JSON, nodeCount, edgeCount}, after mount.
async function renderShapeInto(container, { shape, events, elk, opts, context, isCurrent = () => true }) {
  const _traceLoaded = Array.isArray(events) && events.length > 0;
  const { displayLabel, atomShapeForClass } = classResolver(shape, events || []);
  const { nodes: rawNodes, edges: rawEdges } =
    buildGraph(shape || null, _traceLoaded, events || [], displayLabel, prettifyMsg);
  const { nodes, edges, ownerOf, links, droppedOwnership } = // H2-16
    topology(rawNodes, rawEdges, shape, atomShapeForClass, !!(opts && opts.showInit));
  // These three pieces (blockW / blockHFor / nodesep+ranksep) are the same
  // defaults the legacy viewer uses so v2 inherits a stable look without
  // every caller having to pass them.
  const layoutOpts = Object.assign({
    algorithm: 'layered',
    direction: 'DOWN',
    blockW:    180,
    blockHFor: (n) => n.shape ? 78 : 58,
    nodesep:   56,
    ranksep:   130,
  }, opts || {});
  const result = await layout(elk, nodes, links, ownerOf, layoutOpts);
  // Layout is asynchronous: reject superseded snapshots before touching the DOM.
  if (!isCurrent()) return null;
  const painted = renderSvg(nodes, edges, links, ownerOf, result,
                            Object.assign({ shape, traceLoaded: _traceLoaded }, context || {}));
  container.innerHTML = '';
  container.appendChild(painted.svg);
  // gap 62: include self pills, compound ownership and directional labels.
  const debug = {
    nodes: [...result.pos.entries()].map(([id, p]) => ({id, x:p.x, y:p.y, w:p.w, h:p.h,
      parent:result.parentOf.get(id) || null,
      kind:id.endsWith('__self') ? 'self' : painted.childrenOf.has(id) ? 'container' : 'leaf'})),
    edges: result.layoutEdges.map(e => ({src:e.src, dst:e.dst, elkSrc:e.elkSrc, elkDst:e.elkDst,
      points:e.points.map(p => [p.x,p.y])})), labels:painted.placedLabels,
  };
  // H2-16: keep acyclic debug output identical; expose only rejected cycles.
  if (droppedOwnership.length) debug.droppedOwnership = droppedOwnership;
  return { layout: result, bounds: painted.bounds, debug,
           nodeCount: nodes.length, edgeCount: edges.length };
}

// H2-14: phase request is {atom: shape atom JSON, elk, context, isCurrent}.
// context supplies wireAnnotation, heatOn, heatElement, heatLabel, phaseCount
// and transitionCount with the adapter's existing JS callback contracts.
// Resolves to null when superseded, otherwise {svg, nodeCount, edgeCount}
// with the SVG mounted; the CLJS adapter projects the counts only.
async function renderPhaseGraphInto(container, { atom, elk, context = {}, isCurrent = () => true }) {
  // gaps 23/31: decorate existing phase primitives with targets and heat.
  const {wireAnnotation = () => {}, heatElement = () => {}, heatLabel = () => {},
         phaseCount = () => 0, transitionCount = () => 0, heatOn = false} = context;
  // gap 56: tooltips use declared arm/guard/source metadata.
  const tip = (el, text) => {
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    title.textContent = text; el.appendChild(title);
  };
  const armSummary = arms => arms.map(a => `${a.msg}${a.has_guard ? ' |: guarded' : ''} (line ${a.line ?? '?'})`).join('\n');
  const phaseTarget = (from, to, arms) => {
    const on = [...new Set(arms.map(a => a.msg))].join(', ') || '?';
    return {kind:'edge', target:{from_atom:atom.name, to_atom:atom.name, msg:on, atom:atom.name, from, to, on}};
  };
  container.innerHTML = '';
  const phases = atom.phases.length ? atom.phases : [atom.initial_phase].filter(Boolean);

  const reached = new Set();
  if (atom.initial_phase) reached.add(atom.initial_phase);
  {
    const q = [...reached];
    while (q.length) {
      const p = q.shift();
      for (const t of atom.transitions || []) {
        if (t.from === p && !reached.has(t.to)) { reached.add(t.to); q.push(t.to); }
      }
    }
  }

  const selfPerPhase = new Map();
  for (const t of [...(atom.transitions || []), ...(atom.self_transitions || [])]) {
    const applicable = t.from === '*' && t.to === 'self' ? phases :
      t.from === t.to ? [t.from] : [];
    for (const phase of applicable) {
      if (!selfPerPhase.has(phase)) selfPerPhase.set(phase, []);
      selfPerPhase.get(phase).push(t);
    }
  }

  const pillH = 44;
  const widthFor = (p) => Math.max(84, Math.round((p || '').length * 8.6 + 26));
  const elkNodes = phases.map(p => {
    const n = { id: p, width: widthFor(p), height: pillH };
    if (p === atom.initial_phase) n.layoutOptions = { 'elk.layered.layering.layerConstraint': 'FIRST' };
    if (p === 'end') n.layoutOptions = { 'elk.layered.layering.layerConstraint': 'LAST' };
    return n;
  });

  const edgeGroups = new Map();
  for (const t of atom.transitions || []) {
    if (t.from === t.to) continue;
    if (!phases.includes(t.from) || !phases.includes(t.to)) continue;
    const key = `${t.from}||${t.to}||${t.msg}`;
    if (!edgeGroups.has(key)) edgeGroups.set(key, { from: t.from, to: t.to, msg: t.msg, arms: [], guarded: false });
    const g = edgeGroups.get(key);
    g.arms.push(t);
    if (t.has_guard) g.guarded = true;
  }
  const elkEdges = [...edgeGroups.values()].map((g, i) => {
    const txt = g.msg + (g.arms.length > 1 ? ` ×${g.arms.length}` : '') + (g.guarded ? ' |:' : '');
    const w = Math.max(40, Math.round(txt.length * 7.2 + 10));
    return {
      id: `pe${i}`, sources: [g.from], targets: [g.to],
      labels: [{ text: txt, width: w, height: 14, layoutOptions: { 'elk.edgeLabels.placement': 'CENTER' } }],
      _group: g,
    };
  });

  const elkGraph = {
    id: 'proot',
    layoutOptions: {
      'elk.algorithm': 'layered', 'elk.direction': 'DOWN',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': '48',
      'elk.layered.spacing.nodeNodeBetweenLayers': '72',
      'elk.layered.spacing.edgeNodeBetweenLayers': '24',
      'elk.spacing.edgeEdge': '20', 'elk.spacing.edgeNode': '20', 'elk.spacing.edgeLabel': '8',
      'elk.layered.thoroughness': '50',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.json.edgeCoords': 'ROOT',
    },
    children: elkNodes, edges: elkEdges,
  };

  const result = await elk.layout(elkGraph);
  if (!isCurrent()) return null;

  const selfBadgeSpace = Math.max(0, ...[...selfPerPhase.values()].map(arms => 6 + 32 + String(arms.length).length * 6));
  const padX = Math.max(24, selfBadgeSpace + 8), padY = 24;
  const svgW = Math.ceil((result.width || 400) + padX * 2);
  const svgH = Math.ceil((result.height || 240) + padY * 2);

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', svgW); svg.setAttribute('height', svgH);
  svg.style.flexShrink = '0';

  const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
  const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
  marker.id = 'phase-arrow';
  marker.setAttribute('markerWidth', '12'); marker.setAttribute('markerHeight', '12');
  marker.setAttribute('refX', '11'); marker.setAttribute('refY', '6');
  marker.setAttribute('orient', 'auto'); marker.setAttribute('markerUnits', 'userSpaceOnUse');
  const mp = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  mp.setAttribute('d', 'M0,1 L11,6 L0,11 z'); mp.setAttribute('class', 'mk-ink');
  marker.appendChild(mp); defs.appendChild(marker); svg.appendChild(defs);

  const nodePos = new Map();
  for (const n of (result.children || [])) {
    const x = padX + (n.x || 0), y = padY + (n.y || 0);
    nodePos.set(n.id, { x, y, w: n.width, h: n.height, cx: x + n.width / 2, cy: y + n.height / 2 });
  }

  const groupById = new Map(elkEdges.map(e => [e.id, e._group]));
  for (const e of (result.edges || [])) {
    const group = groupById.get(e.id);
    const annotation = group && phaseTarget(group.from, group.to, group.arms);
    const count = group ? transitionCount(group) : 0;
    const markerId = 'phase-heat-' + e.id;
    if (heatOn) {
      const arrow = marker.cloneNode(true); arrow.id = markerId;
      arrow.querySelector('path').setAttribute('class', 'heat-arrow');
      heatElement(arrow.querySelector('path'), count, false);
      defs.appendChild(arrow);
    }
    for (const s of (e.sections || [])) {
      const pts = [s.startPoint, ...(s.bendPoints || []), s.endPoint].map(pt => ({ x: padX + pt.x, y: padY + pt.y }));
      const path = pointsToPath(pts, 6);
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', path); p.setAttribute('class', 'phase-arc');
      p.setAttribute('marker-end', `url(#${heatOn ? markerId : 'phase-arrow'})`); p.setAttribute('fill', 'none');
      if (annotation) {
        tip(p, `${group.from} → ${group.to}\n${armSummary(group.arms)}`);
        heatElement(p, count, true); wireAnnotation(p, annotation, 'phase-edge');
        const hit = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        hit.setAttribute('d', path); hit.setAttribute('class', 'phase-arc-hit');
        tip(hit, `${group.from} → ${group.to}\n${armSummary(group.arms)}`);
        wireAnnotation(hit, annotation, 'phase-edge', true); svg.appendChild(hit);
      }
      svg.appendChild(p);
    }
    for (const lab of (e.labels || [])) {
      if (lab.x == null) continue;
      const lx = padX + lab.x, ly = padY + lab.y;
      const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      bg.setAttribute('x', lx - 3); bg.setAttribute('y', ly - 1);
      bg.setAttribute('width', lab.width + 6); bg.setAttribute('height', lab.height + 2);
      bg.setAttribute('rx', 2); bg.setAttribute('class', 'phase-arc-label-bg');
      if (group) tip(bg, `${group.from} → ${group.to}\n${armSummary(group.arms)}`);
      heatElement(bg, count); if (annotation) wireAnnotation(bg, annotation, 'phase-arm', true);
      svg.appendChild(bg);
      const tx = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      tx.setAttribute('x', lx + lab.width / 2); tx.setAttribute('y', ly + lab.height / 2);
      tx.setAttribute('text-anchor', 'middle'); tx.setAttribute('class', 'phase-arc-label');
      tx.textContent = lab.text; heatLabel(tx, bg); if (annotation) wireAnnotation(tx, annotation, "phase-arm", true); svg.appendChild(tx);
    }
  }

  for (const [id, pos] of nodePos.entries()) {
    const isInit = id === atom.initial_phase, isEnd = id === 'end';
    const isUnreached = !reached.has(id);
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', pos.x); rect.setAttribute('y', pos.y);
    rect.setAttribute('width', pos.w); rect.setAttribute('height', pos.h);
    rect.setAttribute('rx', 5);
    rect.setAttribute('class', 'phase-node' + (isInit ? ' initial' : '') + (isEnd ? ' end' : '') + (isUnreached ? ' unreached' : ''));
    rect.dataset.phase = id;
    tip(rect, isUnreached ? `${id} — not reachable from the initial phase through declared transitions` :
      `${id}${isInit ? ' — initial phase' : isEnd ? ' — terminal phase' : ' — reachable from initial'}`);
    const annotation = {kind:'node', target:{atom:atom.name, phase:id}};
    wireAnnotation(rect, annotation, 'phase', true); heatElement(rect, phaseCount(id));
    svg.appendChild(rect);
    const tx = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    tx.setAttribute('x', pos.cx); tx.setAttribute('y', pos.cy);
    tx.setAttribute('text-anchor', 'middle');
    tx.setAttribute('class', 'phase-label' + (isInit ? ' initial' : '') + (isUnreached ? ' unreached' : ''));
    tx.textContent = id; heatLabel(tx, rect); wireAnnotation(tx, annotation, "phase-label", true); svg.appendChild(tx);

    if (isUnreached) {
      const caption = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      caption.setAttribute('x', pos.cx); caption.setAttribute('y', pos.y + pos.h + 14);
      caption.setAttribute('text-anchor', 'middle'); caption.setAttribute('class', 'phase-unreached-caption');
      caption.textContent = '(unreached)'; wireAnnotation(caption, annotation, 'unreached-phase', true); svg.appendChild(caption);
    }
    const selfArms = selfPerPhase.get(id);
    if (selfArms && selfArms.length) {
      const digits = String(selfArms.length).length;
      const bw = 32 + digits * 6, bh = 18;
      const bx = pos.x + pos.w + 6, by = pos.cy - bh / 2;
      const badge = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      badge.setAttribute('x', bx); badge.setAttribute('y', by);
      badge.setAttribute('width', bw); badge.setAttribute('height', bh);
      badge.setAttribute('rx', 2); badge.setAttribute('class', 'phase-self-badge');
      tip(badge, `${selfArms.length} self-transitions on ${id} (phase unchanged)\n${armSummary(selfArms)}`);
      const selfAnnotation = phaseTarget(id, id, selfArms);
      const distinct = new Map(selfArms.map(t => [JSON.stringify([t.from,t.to,t.msg]),t]));
      heatElement(badge, [...distinct.values()].reduce((sum,t) => sum + transitionCount(t), 0));
      wireAnnotation(badge, selfAnnotation, 'phase-self', true);
      svg.appendChild(badge);
      const bt = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      bt.setAttribute('x', bx + bw / 2); bt.setAttribute('y', by + bh / 2);
      bt.setAttribute('text-anchor', 'middle');
      bt.setAttribute('class', 'phase-self-badge-label');
      bt.textContent = `self ${selfArms.length}`;
      heatLabel(bt, badge); wireAnnotation(bt, selfAnnotation, 'phase-self-label', true);
      svg.appendChild(bt);
    }
  }

  // H-FRACTAL: publish content bounds so the shared camera (shape-canvas-adapter/content-box)
  // can fit the phase graph instead of falling back to the viewport dimensions.
  svg.dataset.contentX = '0';
  svg.dataset.contentY = '0';
  svg.dataset.contentW = String(svgW);
  svg.dataset.contentH = String(svgH);

  container.appendChild(svg);
  return { svg, nodeCount: phases.length, edgeCount: edgeGroups.size };
}


// gap 11: cycle-safe structural statistics (same SCC definition as legacy).
function shapeChainDepth(atom) {
  const graph = new Map((atom.phases || []).map(p => [p, new Set()]));
  for (const t of atom.transitions || []) {
    if (t.from !== t.to && graph.has(t.from) && graph.has(t.to)) graph.get(t.from).add(t.to);
  }
  const indices = new Map(), low = new Map(), stack = [], active = new Set(), component = new Map();
  let next = 0, components = 0;
  function visit(p) {
    indices.set(p, next); low.set(p, next++); stack.push(p); active.add(p);
    for (const to of graph.get(p)) {
      if (!indices.has(to)) { visit(to); low.set(p, Math.min(low.get(p), low.get(to))); }
      else if (active.has(to)) low.set(p, Math.min(low.get(p), indices.get(to)));
    }
    if (low.get(p) === indices.get(p)) {
      let q;
      do { q = stack.pop(); active.delete(q); component.set(q, components); } while (q !== p);
      components++;
    }
  }
  for (const p of graph.keys()) if (!indices.has(p)) visit(p);
  const dag = Array.from({length:components}, () => new Set());
  for (const [from, tos] of graph) for (const to of tos) {
    if (component.get(from) !== component.get(to)) dag[component.get(from)].add(component.get(to));
  }
  const depths = new Map();
  function depth(id) {
    if (!depths.has(id)) depths.set(id, Math.max(0, ...[...dag[id]].map(to => 1 + depth(to))));
    return depths.get(id);
  }
  return Math.max(0, ...dag.map((_, id) => depth(id)));
}

// gap 31: infer phase/transition hits per instance, deduplicating guarded arrows.
function phaseHeat(shape, events) {
  const {atomShapeForClass, displayLabel} = classResolver(shape, events);
  const _atomAnnName = displayLabel;
  const _heatState = {atoms:new Map(), phases:new Map(), transitions:new Map()};
  const heatPhaseKey = (atom, phase) => JSON.stringify([atom, phase]);
  const heatTransitionKey = (atom, t) => JSON.stringify([atom, t.from, t.to, t.msg]);
  const currentPhases = new Map();
  const increment = (map, key) => map.set(key, (map.get(key) || 0) + 1);
  for (const e of events) {
    const cls = e.target_cls || e.atom_cls || (typeof e.atom === 'string' ? e.atom : null);
    const atom = atomShapeForClass(cls);
    const name = atom?.name || _atomAnnName(cls);
    increment(_heatState.atoms, name);
    if (!atom) continue;
    const instance = JSON.stringify([name, e.target ?? e.atom ?? null]);
    const initial = atom.initial_phase || ((atom.phases || []).length === 1 ? (atom.phases || [])[0] : null);
    const previous = currentPhases.has(instance) ? currentPhases.get(instance) : initial;
    const explicitPhase = (atom.phases || []).includes(e.phase) ? e.phase : null;
    // Newer traces name the phase/arm directly. Bootstrap traces use arm/msg;
    // infer their phase only while the instance's state remains unambiguous.
    const transition = e.transition && typeof e.transition === 'object' ? e.transition : null;
    const arm = e.arm_name ?? transition?.msg ?? e.transition ?? (e.kind === 'arm' ? e.msg : null);
    let matches = [];
    if (typeof arm === 'string') {
      matches = [...(atom.transitions || []), ...(atom.self_transitions || [])].filter(t =>
        [t.msg, t.arm_name, t.name].filter(Boolean).some(m => m === arm || m === prettifyMsg(arm)));
      const from = transition?.from ?? e.from_phase ?? explicitPhase ?? previous;
      const fromMatches = matches.filter(t => t.from === from || t.from === '*');
      if (fromMatches.length) matches = fromMatches;
      const to = transition?.to ?? e.to_phase;
      if (to) matches = matches.filter(t => t.to === to || t.to === 'self');
      // Duplicate guarded arms share one arrow; count an event once per arrow.
      for (const key of new Set(matches.map(t => heatTransitionKey(name, t)))) increment(_heatState.transitions, key);
    }
    const sources = new Set(matches.map(t => t.from === '*' ? previous : t.from).filter(Boolean));
    const phase = explicitPhase || (sources.size === 1 ? [...sources][0] : previous);
    if (phase) increment(_heatState.phases, heatPhaseKey(name, phase));
    const destinations = new Set(matches.map(t => t.to === 'self' ? phase : t.to));
    currentPhases.set(instance, destinations.size === 1 ? [...destinations][0] : matches.length ? null : explicitPhase || previous);
  }
  return {phases:Object.fromEntries(_heatState.phases), transitions:Object.fromEntries(_heatState.transitions)};
}

// H2-09/H2-10: the CLJS palette publishes status -> CSS paint strings at init.
// Copy the handoff so callers cannot change a painter's palette in place.
let statusColors = Object.freeze({});
function setStatusColors(colors) {
  statusColors = Object.freeze({ ...colors });
}

// gap 45: refreshable annotation overlays, using the shared class resolver.
function renderAnnotationBadges(svg, anns, resolver, context) {
  if (!svg) return;
  const {wireAnnotation:_wireAnnotation, selectNode} = context;
  const annEffectiveStatus = a => a.effective_status || a.status || 'open';
  // H2-09/H2-10: status colors are data supplied by the host, not a second table.
  for (const old of svg.querySelectorAll('.ann-badge')) old.remove();
  const byAtom = new Map();
  for (const a of anns) {
    const raw = typeof a.target?.atom === 'string' ? a.target.atom :
      a.target?.from_atom || a.target?.atom_cls || a.target?.src;
    // Annotation targets can be either user-visible atom names (:atom,
    // :from_atom) or mangled class strings (:atom_cls, :src). Try the
    // name path first so an annotation targeting user-named atom X
    // isn't class-first-remapped onto another atom whose mangled class
    // happens to equal X (round-8 raw-name-shadow collateral). The
    // class-based resolver stays as the fallback for :atom_cls / :src.
    const direct = raw && resolver.atomByName && resolver.atomByName(raw);
    const name = (direct && direct.name)
      || (raw && (resolver.atomShapeForClass(raw)?.name || resolver.displayLabel(raw)));
    if (!name) continue;
    if (!byAtom.has(name)) byAtom.set(name, []);
    byAtom.get(name).push(a);
  }
  for (const [name, group] of byAtom) {
    const blocks = [...svg.querySelectorAll('[data-node-name="' + CSS.escape(name) + '"]')];
    const block = blocks.find(b => !b.classList.contains('container')) || blocks[0];
    if (!block) continue;
    const bbox = block.getBBox();
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    g.setAttribute('class', 'ann-badge');
    g.style.pointerEvents = 'all';
    g.style.cursor = 'pointer';
    _wireAnnotation(g, {kind:'node', target:{atom:name}}, 'annotation-badge', true);
    g.addEventListener('click', () => selectNode({cls:name}));
    const worst = group.map(annEffectiveStatus).sort((a, b) => statusRank(a) - statusRank(b))[0];
    const description = `${group.length} annotation${group.length === 1 ? '' : 's'} on ${name} · worst status: ${worst} · ${group.map(a => a.id).join(', ')} · click to view atom · Shift-click or right-click to annotate`;
    const title = document.createElementNS('http://www.w3.org/2000/svg', 'title');
    title.textContent = description;
    g.appendChild(title);
    g.setAttribute('aria-label', description + ' · Enter to annotate');
    const bw = Math.max(20, String(group.length).length * 6 + 10);
    const cx = bbox.x + bbox.width - (group.length > 1 ? 6 + bw / 2 : 10);
    const cy = bbox.y + (group.length > 1 ? 12 : 10);
    // One annotation stays a status dot. Counts use a speech bubble so the
    // digit reads as annotations; width grows with the number of digits.
    const mark = document.createElementNS('http://www.w3.org/2000/svg', group.length > 1 ? 'path' : 'circle');
    if (group.length > 1) {
      const x = cx - bw / 2, y = cy - 8;
      mark.setAttribute('d', `M${x+3},${y} H${x+bw-3} Q${x+bw},${y} ${x+bw},${y+3} V${y+13} Q${x+bw},${y+16} ${x+bw-3},${y+16} H${x+8} L${x+4},${y+19} V${y+16} H${x+3} Q${x},${y+16} ${x},${y+13} V${y+3} Q${x},${y} ${x+3},${y} Z`);
    } else {
      mark.setAttribute('cx', cx); mark.setAttribute('cy', cy); mark.setAttribute('r', 5);
    }
    mark.setAttribute('fill', statusColors[worst] || 'var(--p-ink2)'); // H2-09/H2-10
    mark.setAttribute('stroke', 'var(--p-paper)');
    mark.setAttribute('stroke-width', '1');
    g.appendChild(mark);
    if (group.length > 1) {
      const t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      t.setAttribute('x', cx); t.setAttribute('y', cy);
      t.setAttribute('text-anchor', 'middle');
      t.setAttribute('font-size', '10');
      t.setAttribute('font-family', 'IBM Plex Mono,monospace');
      t.setAttribute('fill', 'var(--p-paper)');
      t.style.pointerEvents = 'none';
      t.textContent = group.length;
      g.appendChild(t);
    }
    svg.insertBefore(g, svg.querySelector('.cxc-obs-overlay'));
  }
}

function statusRank(s) {
  // Draft ranks after open: an unsubmitted draft is quieter than a submitted
  // open annotation when both target the same atom. Terminal states rank last.
  const order = ['open', 'in_progress', 'addressed', 'draft', 'stale', 'wontfix', 'verified'];
  const i = order.indexOf(s);
  return i < 0 ? order.length : i;
}

window.CxcShapeCanvas = { setStatusColors, enableSynchronousElk, phaseHeat, shapeChainDepth, renderAnnotationBadges, algorithms, directions, usesDirection, availableAlgorithms, pointsToPath, edgeLabelText, edgeLabelWidth, pairEdges, travelGlyph, prettifyMsg, prettifyContainerName, classResolver, buildGraph, topology, layout, renderSvg, metrics, renderShapeInto, renderPhaseGraphInto }; // H2-09/H2-10
})();
