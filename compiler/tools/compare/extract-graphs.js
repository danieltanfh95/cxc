#!/usr/bin/env node
// Emits `cards.rs.graph.json` and `cards.ts.graph.json` next to the sources.
// Uses tree-sitter grammars for Rust and TypeScript, so the graph is a real
// AST walk, not a regex. Each top-level declaration becomes a compound node
// with its methods / variants / fields / signatures as children. Cross-decl
// edges come from identifier references in each decl's body.

const fs = require('fs');
const path = require('path');
const Parser = require('tree-sitter');
const Rust = require('tree-sitter-rust');
const TS = require('tree-sitter-typescript').typescript;

const SRC_DIR = __dirname;

function textOf(node, src) {
  return src.slice(node.startIndex, node.endIndex);
}

function walkText(node, src, cb) {
  cb(node);
  for (let i = 0; i < node.childCount; i++) walkText(node.child(i), src, cb);
}

function collectIdents(node, src) {
  const out = new Set();
  walkText(node, src, n => {
    if (n.type === 'identifier' || n.type === 'type_identifier') {
      out.add(textOf(n, src));
    }
  });
  return out;
}

function dedupBy(xs, key) {
  const seen = new Set();
  const out = [];
  for (const x of xs) {
    const k = key(x);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(x);
  }
  return out;
}

// -- Rust --------------------------------------------------------------------

function extractRust(src) {
  const parser = new Parser();
  parser.setLanguage(Rust);
  const tree = parser.parse(src);
  const root = tree.rootNode;
  const containers = new Map(); // name → {node, kind, sources: [sourceNode]}
  const freeFns = [];
  function record(name, kind, srcNode) {
    if (!containers.has(name)) {
      containers.set(name, { kind, sources: [], children: [] });
    }
    containers.get(name).sources.push(srcNode);
  }
  for (let i = 0; i < root.childCount; i++) {
    const n = root.child(i);
    switch (n.type) {
      case 'struct_item': {
        const name = n.childForFieldName('name');
        if (name) record(textOf(name, src), 'atom', n);
        break;
      }
      case 'enum_item': {
        const name = n.childForFieldName('name');
        if (!name) break;
        const nm = textOf(name, src);
        record(nm, 'enum', n);
        const body = n.childForFieldName('body');
        if (body) {
          for (let j = 0; j < body.childCount; j++) {
            const v = body.child(j);
            if (v.type === 'enum_variant') {
              const vn = v.childForFieldName('name');
              if (vn) containers.get(nm).children.push({
                id: `${nm}/variant/${textOf(vn, src)}`,
                label: textOf(vn, src),
                kind: 'variant',
              });
            }
          }
        }
        break;
      }
      case 'trait_item': {
        const name = n.childForFieldName('name');
        if (name) record(textOf(name, src), 'trait', n);
        break;
      }
      case 'impl_item': {
        const typeName = n.childForFieldName('type');
        if (!typeName) break;
        const nm = textOf(typeName, src);
        // ensure container exists even if struct is in another impl-only module
        if (!containers.has(nm)) containers.set(nm, { kind: 'atom', sources: [], children: [] });
        containers.get(nm).sources.push(n);
        const body = n.childForFieldName('body');
        if (body) {
          for (let j = 0; j < body.childCount; j++) {
            const m = body.child(j);
            if (m.type === 'function_item') {
              const fn = m.childForFieldName('name');
              if (fn) containers.get(nm).children.push({
                id: `${nm}/fn/${textOf(fn, src)}`,
                label: textOf(fn, src) + '(…)',
                kind: 'method',
              });
            }
          }
        }
        break;
      }
      case 'function_item': {
        const name = n.childForFieldName('name');
        if (!name) break;
        const nm = textOf(name, src);
        freeFns.push({
          id: nm,
          label: nm,
          kind: nm === 'main' ? 'main' : 'fn',
          sources: [n],
          children: [],
        });
        break;
      }
    }
  }
  const nodes = [];
  for (const [name, info] of containers) {
    const seen = new Set();
    const dedup = [];
    for (const c of info.children) {
      if (seen.has(c.id)) continue;
      seen.add(c.id);
      dedup.push(c);
    }
    nodes.push({ id: name, label: name, kind: info.kind, children: dedup, _sources: info.sources });
  }
  for (const fn of freeFns) nodes.push({ id: fn.id, label: fn.label, kind: fn.kind, children: fn.children, _sources: fn.sources });
  // Edges: for each node, scan its sources for identifiers that match other node names.
  const nameSet = new Set(nodes.map(n => n.id));
  const edges = [];
  for (const n of nodes) {
    const idents = new Set();
    for (const sn of (n._sources || [])) {
      for (const i of collectIdents(sn, src)) idents.add(i);
    }
    for (const i of idents) {
      if (i !== n.id && nameSet.has(i)) edges.push({ src: n.id, dst: i });
    }
    delete n._sources;
  }
  return { nodes, edges: dedupBy(edges, e => `${e.src}→${e.dst}`) };
}

// -- TypeScript --------------------------------------------------------------

function extractTs(src) {
  const parser = new Parser();
  parser.setLanguage(TS);
  const tree = parser.parse(src);
  const root = tree.rootNode;
  const nodes = [];
  function addNode(id, label, kind, srcNode) {
    const n = { id, label, kind, children: [], _source: srcNode };
    nodes.push(n);
    return n;
  }
  for (let i = 0; i < root.childCount; i++) {
    const n = root.child(i);
    // Unwrap `export_statement`
    let decl = n;
    if (decl.type === 'export_statement') {
      decl = decl.childForFieldName('declaration') || decl.child(1) || decl;
    }
    switch (decl.type) {
      case 'class_declaration': {
        const nm = decl.childForFieldName('name');
        if (!nm) break;
        const name = textOf(nm, src);
        const container = addNode(name, name, 'atom', decl);
        const body = decl.childForFieldName('body');
        if (body) {
          for (let j = 0; j < body.childCount; j++) {
            const m = body.child(j);
            if (m.type === 'method_definition' || m.type === 'public_field_definition') {
              const mn = m.childForFieldName('name');
              if (mn) {
                const isMethod = m.type === 'method_definition';
                container.children.push({
                  id: `${name}/mem/${textOf(mn, src)}`,
                  label: textOf(mn, src) + (isMethod ? '(…)' : ''),
                  kind: 'method',
                });
              }
            }
          }
        }
        break;
      }
      case 'interface_declaration': {
        const nm = decl.childForFieldName('name');
        if (!nm) break;
        const name = textOf(nm, src);
        const container = addNode(name, name, 'interface', decl);
        const body = decl.childForFieldName('body');
        if (body) {
          for (let j = 0; j < body.childCount; j++) {
            const m = body.child(j);
            // method_signature | property_signature | construct_signature | …
            const mn = m.childForFieldName('name');
            if (mn) {
              const isMethod = m.type === 'method_signature' || m.type === 'construct_signature' || m.type === 'call_signature';
              container.children.push({
                id: `${name}/mem/${textOf(mn, src)}`,
                label: textOf(mn, src) + (isMethod ? '(…)' : ''),
                kind: 'method',
              });
            }
          }
        }
        break;
      }
      case 'type_alias_declaration': {
        const nm = decl.childForFieldName('name');
        if (nm) addNode(textOf(nm, src), textOf(nm, src), 'type', decl);
        break;
      }
      case 'enum_declaration': {
        const nm = decl.childForFieldName('name');
        if (!nm) break;
        const name = textOf(nm, src);
        const container = addNode(name, name, 'enum', decl);
        const body = decl.childForFieldName('body');
        if (body) {
          for (let j = 0; j < body.childCount; j++) {
            const m = body.child(j);
            if (m.type === 'enum_assignment' || m.type === 'property_identifier') {
              const nameNode = m.childForFieldName('name') || (m.type === 'property_identifier' ? m : null);
              if (nameNode) container.children.push({
                id: `${name}/variant/${textOf(nameNode, src)}`,
                label: textOf(nameNode, src),
                kind: 'variant',
              });
            }
          }
        }
        break;
      }
      case 'function_declaration': {
        const nm = decl.childForFieldName('name');
        if (!nm) break;
        const name = textOf(nm, src);
        addNode(name, name, name === 'main' ? 'main' : 'fn', decl);
        break;
      }
      case 'lexical_declaration':
      case 'variable_declaration': {
        for (let j = 0; j < decl.childCount; j++) {
          const d = decl.child(j);
          if (d.type !== 'variable_declarator') continue;
          const nm = d.childForFieldName('name');
          if (!nm) continue;
          const name = textOf(nm, src);
          // Keep SCREAMING_SNAKE_CASE top-level constants only.
          if (/^[A-Z][A-Z0-9_]*$/.test(name)) addNode(name, name, 'const', d);
        }
        break;
      }
    }
  }
  const nameSet = new Set(nodes.map(n => n.id));
  const edges = [];
  for (const n of nodes) {
    const idents = collectIdents(n._source, src);
    for (const i of idents) {
      if (i !== n.id && nameSet.has(i)) edges.push({ src: n.id, dst: i });
    }
    delete n._source;
  }
  return { nodes, edges: dedupBy(edges, e => `${e.src}→${e.dst}`) };
}

// -- main --------------------------------------------------------------------

function run(file, extractor, outName) {
  const srcPath = path.join(SRC_DIR, file);
  if (!fs.existsSync(srcPath)) {
    console.error('missing', srcPath);
    return;
  }
  const src = fs.readFileSync(srcPath, 'utf-8');
  const graph = extractor(src);
  const outPath = path.join(SRC_DIR, outName);
  fs.writeFileSync(outPath, JSON.stringify(graph, null, 2));
  const sumKinds = {};
  let members = 0;
  for (const n of graph.nodes) {
    sumKinds[n.kind] = (sumKinds[n.kind] || 0) + 1;
    members += (n.children?.length || 0);
  }
  console.log(`${outName}: nodes=${graph.nodes.length} members=${members} edges=${graph.edges.length}`, sumKinds);
}

run('cards.rs', extractRust, 'cards.rs.graph.json');
run('cards.ts', extractTs, 'cards.ts.graph.json');
