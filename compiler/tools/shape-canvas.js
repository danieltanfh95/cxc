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
        id: e.src + '__' + e.dst,
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
      layoutEdges.push({src:e.sources[0].replace(/__self$/, ''), dst:e.targets[0].replace(/__self$/, ''),
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

function buildGraph(_shape, _traceLoaded, filtered, displayLabel, prettifyMsg) {
  // Collect observed classes here; renderShape restricts them to declared
  // atoms when a shape is available. Without one, keep the full trace graph.

  const nodes = new Map();
  const edges = new Map();
  if (_shape && !_traceLoaded) {
    return {nodes:_shape.atoms.map(a => ({id:a.name, label:a.name, cls:a.name, shape:a, size:0})), edges:[]};
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
  return {nodes: [...nodes.values()], edges: [...edges.values()]};
}



function topology(nodes, edges, _shape, atomShapeForClass, showInit = false) {
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
      const s = atomShapeForClass(n.cls);
      if (s) { n.shape = s; n.label = s.name; kept.set(n.id, n); }
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
  for (const n of nodes) {
    if (!n.shape) continue;
    const ctorNames = new Set((n.shape.ctor_params || []).map(p => p.name));
    for (const f of (n.shape.fields || [])) {
      if (ctorNames.has(f.name)) continue;
      const childCls = stripContainer(f.type);
      const childId = clsToId.get(childCls);
      if (!childId || !nodeIds.has(childId) || childId === n.id) continue;
      if (ownerOf.has(childId)) continue;
      ownerOf.set(childId, n.id);
    }
  }
  // One link per atom pair, keyed by its primary direction (the ELK edge's
  // direction).
  const links = pairEdges(edges);
  return { nodes, edges, ownerOf, links };
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
  const linkOf = new Map(links.map(l => [l.e.src + '|' + l.e.dst, l]));
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
    const link = linkOf.get(layoutEdge.src + '|' + layoutEdge.dst);
    if (!link) continue;
    if (!layoutEdge.points || !layoutEdge.points.length) continue;
    const { e, rev } = link;
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
    const isSelected = selectedNode === n.id || (n.shape && selectedNode === n.shape.name);
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
    const isSelected = selectedNode === parentId || (parent.shape && selectedNode === parent.shape.name);
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
  const extents = [...placedLabels, ...tabRects];
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

// One-call convenience for the CLJS viewer. Takes a shape JSON (may be
// null for a trace-only view), the filtered event list, an elkjs instance,
// an opts bag (algorithm, direction, showInit), and the host container.
// Resolves to the raw layout result, with the SVG already mounted.
async function renderShapeInto(container, { shape, events, elk, opts, context }) {
  const _traceLoaded = Array.isArray(events) && events.length > 0;
  const displayLabel = (id) => (id.includes('.') ? id.split('.').pop() : id);
  const { nodes: rawNodes, edges: rawEdges } =
    buildGraph(shape || null, _traceLoaded, events || [], displayLabel, prettifyMsg);
  const atomShapeForClass = (cls) =>
    shape && shape.atoms ? (shape.atoms.find(a => a.name === cls) || null) : null;
  const { nodes, edges, ownerOf, links } =
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
  const painted = renderSvg(nodes, edges, links, ownerOf, result,
                            Object.assign({ shape, traceLoaded: _traceLoaded }, context || {}));
  container.innerHTML = '';
  container.appendChild(painted.svg);
  return { layout: result, bounds: painted.bounds,
           nodeCount: nodes.length, edgeCount: edges.length };
}

// Phase-graph render for the atom-detail pane. Ported from the legacy
// viewer so both v2 and legacy share one source of truth. Takes an atom
// (as parsed from the shape JSON) and an elkjs instance, returns an svg
// mounted into the container. No heat / annotation wiring — the caller
// decorates the DOM after this returns.
async function renderPhaseGraphInto(container, { atom, elk }) {
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
  for (const t of atom.transitions || []) {
    if (t.from !== t.to) continue;
    if (!selfPerPhase.has(t.from)) selfPerPhase.set(t.from, []);
    selfPerPhase.get(t.from).push(t);
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

  for (const e of (result.edges || [])) {
    for (const s of (e.sections || [])) {
      const pts = [s.startPoint, ...(s.bendPoints || []), s.endPoint].map(pt => ({ x: padX + pt.x, y: padY + pt.y }));
      const path = pointsToPath(pts, 6);
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', path); p.setAttribute('class', 'phase-arc');
      p.setAttribute('marker-end', 'url(#phase-arrow)'); p.setAttribute('fill', 'none');
      svg.appendChild(p);
    }
    for (const lab of (e.labels || [])) {
      if (lab.x == null) continue;
      const lx = padX + lab.x, ly = padY + lab.y;
      const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      bg.setAttribute('x', lx - 3); bg.setAttribute('y', ly - 1);
      bg.setAttribute('width', lab.width + 6); bg.setAttribute('height', lab.height + 2);
      bg.setAttribute('rx', 2); bg.setAttribute('class', 'phase-arc-label-bg');
      svg.appendChild(bg);
      const tx = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      tx.setAttribute('x', lx + lab.width / 2); tx.setAttribute('y', ly + lab.height / 2);
      tx.setAttribute('text-anchor', 'middle'); tx.setAttribute('class', 'phase-arc-label');
      tx.textContent = lab.text; svg.appendChild(tx);
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
    svg.appendChild(rect);
    const tx = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    tx.setAttribute('x', pos.cx); tx.setAttribute('y', pos.cy);
    tx.setAttribute('text-anchor', 'middle');
    tx.setAttribute('class', 'phase-label' + (isInit ? ' initial' : '') + (isUnreached ? ' unreached' : ''));
    tx.textContent = id; svg.appendChild(tx);

    const selfArms = selfPerPhase.get(id);
    if (selfArms && selfArms.length) {
      const digits = String(selfArms.length).length;
      const bw = 32 + digits * 6, bh = 18;
      const bx = pos.x + pos.w + 6, by = pos.cy - bh / 2;
      const badge = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      badge.setAttribute('x', bx); badge.setAttribute('y', by);
      badge.setAttribute('width', bw); badge.setAttribute('height', bh);
      badge.setAttribute('rx', 2); badge.setAttribute('class', 'phase-self-badge');
      svg.appendChild(badge);
      const bt = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      bt.setAttribute('x', bx + bw / 2); bt.setAttribute('y', by + bh / 2);
      bt.setAttribute('text-anchor', 'middle');
      bt.setAttribute('class', 'phase-self-badge-label');
      bt.textContent = `self ${selfArms.length}`;
      svg.appendChild(bt);
    }
  }

  container.appendChild(svg);
  return { svg, nodeCount: phases.length, edgeCount: edgeGroups.size };
}

window.CxcShapeCanvas = { algorithms, directions, usesDirection, availableAlgorithms, pointsToPath, edgeLabelText, edgeLabelWidth, pairEdges, travelGlyph, prettifyMsg, prettifyContainerName, buildGraph, topology, layout, renderSvg, metrics, renderShapeInto, renderPhaseGraphInto };
})();
