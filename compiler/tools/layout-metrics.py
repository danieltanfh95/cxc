#!/usr/bin/env python3
"""Score a trace-viewer shape layout against graph-drawing readability criteria.

Input is the geometry dump trace-viewer.html writes to <script
id="layout-debug"> when loaded with ?sync=1, either as raw JSON or inside a
`chrome --dump-dom` HTML file.

Checks and where they come from:
  endpoint gap        correctness: an edge must touch the node it names
  crossings / angle   Purchase 1997 (crossings dominate); Huang-Hong-Eades 2008
                      (small crossing angles slow path tracing, ~90deg best)
  bends               Purchase 1997; Ware et al. 2002 (path continuity)
  edge-through-node   no edge may pass over a node it does not touch
  edge-region excess  clustered-graph c-planarity: an edge crosses a cluster
                      boundary at most once, and only if exactly one endpoint
                      is inside (Feng-Cohen-Eades 1995; Angelini et al. 2014)
  label overlaps      Kakoulis-Tollis rule 1 (after Imhof): labels overlap
                      nothing
  label association   Kakoulis-Tollis rule 2: a label touches its own edge and
                      no other edge is nearer
  label row crowding  Gestalt proximity: labels sharing a row need a gap
                      clearly wider than a word space
"""
import json
import math
import re
import sys

TOL = 2.0
# trace-viewer sets elk.spacing.edgeLabel to 2, so a label ELK places beside
# its edge (self-loop labels) sits 2px off it and still counts as attached.
EDGE_LABEL_SPACING = 2.0
LABEL_TOL = EDGE_LABEL_SPACING + TOL


def load(path):
    text = open(path, encoding="utf-8").read()
    if path.endswith(".json"):
        return json.loads(text)
    m = re.search(r'<script[^>]*id="layout-debug"[^>]*>(.*?)</script>', text, re.S)
    if not m:
        sys.exit(f"no layout-debug blob in {path}")
    return json.loads(m.group(1))


def segs(points):
    return [(points[i], points[i + 1]) for i in range(len(points) - 1)]


def dist_point_seg(p, a, b):
    ax, ay = a
    bx, by = b
    px, py = p
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    if L == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def dist_point_rect_border(p, r):
    x, y = p
    x0, y0, x1, y1 = r["x"], r["y"], r["x"] + r["w"], r["y"] + r["h"]
    inside = x0 <= x <= x1 and y0 <= y <= y1
    if inside:
        return min(x - x0, x1 - x, y - y0, y1 - y)
    dx = max(x0 - x, 0, x - x1)
    dy = max(y0 - y, 0, y - y1)
    return math.hypot(dx, dy)


def seg_intersection(a, b, c, d):
    """Proper intersection point of segments ab and cd, or None."""
    (x1, y1), (x2, y2), (x3, y3), (x4, y4) = a, b, c, d
    den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if abs(den) < 1e-9:
        return None
    t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
    u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den
    eps = 1e-6
    if eps < t < 1 - eps and eps < u < 1 - eps:
        return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    return None


def angle_between(a, b, c, d):
    v1 = (b[0] - a[0], b[1] - a[1])
    v2 = (d[0] - c[0], d[1] - c[1])
    n1, n2 = math.hypot(*v1), math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return 90.0
    cos = abs(v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)
    return math.degrees(math.acos(max(-1.0, min(1.0, cos))))


def seg_hits_rect_interior(a, b, r, shrink=1.0):
    x0, y0 = r["x"] + shrink, r["y"] + shrink
    x1, y1 = r["x"] + r["w"] - shrink, r["y"] + r["h"] - shrink
    if x1 <= x0 or y1 <= y0:
        return False
    # Liang-Barsky clip
    dx, dy = b[0] - a[0], b[1] - a[1]
    t0, t1 = 0.0, 1.0
    for p, q in ((-dx, a[0] - x0), (dx, x1 - a[0]), (-dy, a[1] - y0), (dy, y1 - a[1])):
        if p == 0:
            if q < 0:
                return False
        else:
            t = q / p
            if p < 0:
                t0 = max(t0, t)
            else:
                t1 = min(t1, t)
            if t0 > t1:
                return False
    return t1 - t0 > 1e-6


def dist_rect_polyline(r, points):
    """0 if the polyline touches r, else the gap between them."""
    best = 1e9
    corners = [(r["x"], r["y"]), (r["x"] + r["w"], r["y"]),
               (r["x"], r["y"] + r["h"]), (r["x"] + r["w"], r["y"] + r["h"])]
    for a, b in segs(points):
        if seg_hits_rect_interior(a, b, r, shrink=0):
            return 0.0
        for q in (a, b):
            dx = max(r["x"] - q[0], 0, q[0] - r["x"] - r["w"])
            dy = max(r["y"] - q[1], 0, q[1] - r["y"] - r["h"])
            best = min(best, math.hypot(dx, dy))
        for c in corners:
            best = min(best, dist_point_seg(c, a, b))
    return best


def rects_overlap(a, b, pad=0.0):
    return not (a["x"] + a["w"] <= b["x"] + pad or b["x"] + b["w"] <= a["x"] + pad
                or a["y"] + a["h"] <= b["y"] + pad or b["y"] + b["h"] <= a["y"] + pad)


def boundary_crossings(points, r, step=2.0):
    """Count inside/outside transitions of a polyline against rect r."""
    def inside(p):
        return r["x"] < p[0] < r["x"] + r["w"] and r["y"] < p[1] < r["y"] + r["h"]
    state = None
    n = 0
    for a, b in segs(points):
        L = math.hypot(b[0] - a[0], b[1] - a[1])
        k = max(1, int(L / step))
        for i in range(k + 1):
            t = i / k
            s = inside((a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])))
            if state is not None and s != state:
                n += 1
            state = s
    return n


def main():
    data = load(sys.argv[1])
    nodes = {n["id"]: n for n in data["nodes"]}
    edges = data["edges"]
    labels = data["labels"] + data.get("selfLoops", [])
    pills = [n for n in nodes.values() if n["kind"] in ("leaf", "self")]
    containers = [n for n in nodes.values() if n["kind"] == "container"]

    def ancestors(nid):
        out = []
        cur = nodes.get(nid, {}).get("parent")
        while cur:
            out.append(cur)
            cur = nodes.get(cur, {}).get("parent")
        return out

    def eid(e):
        return f'{e["src"]}->{e["dst"]}'

    report = {}
    findings = []

    # endpoint gap
    gaps = []
    for e in edges:
        pts = e["points"]
        if len(pts) < 2:
            continue
        gs = dist_point_rect_border(pts[0], nodes[e["elkSrc"]])
        gd = dist_point_rect_border(pts[-1], nodes[e["elkDst"]])
        gaps.append(max(gs, gd))
        if gs > TOL or gd > TOL:
            findings.append(f"endpoint-gap {eid(e)}: start {gs:.0f}px from {e['elkSrc']}, end {gd:.0f}px from {e['elkDst']}")
    report["endpoint gap >2px"] = sum(1 for g in gaps if g > TOL)
    report["endpoint gap max px"] = round(max(gaps), 1) if gaps else 0

    # crossings
    crossings = []
    for i in range(len(edges)):
        for j in range(i + 1, len(edges)):
            for a, b in segs(edges[i]["points"]):
                for c, d in segs(edges[j]["points"]):
                    if seg_intersection(a, b, c, d):
                        crossings.append(angle_between(a, b, c, d))
    report["edge crossings"] = len(crossings)
    report["min crossing angle"] = round(min(crossings), 1) if crossings else None

    # bends
    bends = [max(0, len(e["points"]) - 2) for e in edges]
    report["bends total"] = sum(bends)
    report["bends max/edge"] = max(bends) if bends else 0

    # edge through node
    etn = 0
    for e in edges:
        for n in pills:
            if n["id"] in (e["elkSrc"], e["elkDst"]):
                continue
            if any(seg_hits_rect_interior(a, b, n) for a, b in segs(e["points"])):
                etn += 1
                findings.append(f"edge-through-node {eid(e)} passes over {n['id']}")
    report["edge through node"] = etn

    # edge-region excess
    excess_total = 0
    for e in edges:
        src_anc = set(ancestors(e["elkSrc"]))
        dst_anc = set(ancestors(e["elkDst"]))
        for c in containers:
            necessary = int((c["id"] in src_anc) != (c["id"] in dst_anc))
            got = boundary_crossings(e["points"], c)
            if got > necessary:
                excess_total += got - necessary
                findings.append(f"edge-region {eid(e)} crosses {c['id']} border {got}x (needs {necessary})")
    report["edge-region excess"] = excess_total

    # labels
    lo_label = lo_node = lo_edge = detached = ambiguous = 0
    all_polys = [(f'edge-{e["src"]}--{e["dst"]}'.replace(" ", "_"), e["points"]) for e in edges]
    all_polys += [(s["edgeId"], s["points"]) for s in data.get("selfLoops", [])]

    def norm(s):
        return re.sub(r"[^a-zA-Z0-9-]", "_", s)

    polys = {norm(k): v for k, v in all_polys}
    for i, L in enumerate(labels):
        for M in labels[i + 1:]:
            if rects_overlap(L, M):
                lo_label += 1
                findings.append(f"label-overlap '{L['text']}' x '{M['text']}'")
        for n in pills:
            if rects_overlap(L, n):
                lo_node += 1
                findings.append(f"label-over-node '{L['text']}' on {n['id']}")
        own = polys.get(L["edgeId"])
        own_d = dist_rect_polyline(L, own) if own else 1e9
        if own_d > LABEL_TOL:
            detached += 1
            findings.append(f"label-detached '{L['text']}' {own_d:.0f}px from its edge")
        for k, pts in polys.items():
            if k == L["edgeId"]:
                continue
            if any(seg_hits_rect_interior(a, b, L, shrink=0) for a, b in segs(pts)):
                lo_edge += 1
                findings.append(f"label-over-edge '{L['text']}' crossed by {k}")
            d = dist_rect_polyline(L, pts)
            if own_d > LABEL_TOL and d + 4 < own_d:
                ambiguous += 1
                findings.append(f"label-ambiguous '{L['text']}' nearer to {k} ({d:.0f}px) than own ({own_d:.0f}px)")
    report["label x label overlap"] = lo_label
    report["label x node overlap"] = lo_node
    report["label crossed by other edge"] = lo_edge
    report["label detached from edge"] = detached
    report["label nearer another edge"] = ambiguous

    # label row crowding. A 16px rect gap is about 28px between the texts
    # (6px chip padding each side), roughly 3.5 monospace characters at 13px.
    # The threshold is a heuristic.
    ROW_GAP = 16.0
    crowded = 0
    min_row_gap = None
    for i, L in enumerate(labels):
        for M in labels[i + 1:]:
            vo = min(L["y"] + L["h"], M["y"] + M["h"]) - max(L["y"], M["y"])
            if vo < 0.5 * min(L["h"], M["h"]):
                continue
            gap = max(M["x"] - (L["x"] + L["w"]), L["x"] - (M["x"] + M["w"]))
            if gap < 0:
                continue
            min_row_gap = gap if min_row_gap is None else min(min_row_gap, gap)
            if gap < ROW_GAP:
                crowded += 1
                findings.append(f"label-row-crowded '{L['text']}' | '{M['text']}' gap {gap:.0f}px")
    report["label row crowded (<16px)"] = crowded
    report["label row min gap px"] = round(min_row_gap, 1) if min_row_gap is not None else None

    xs = [n["x"] for n in nodes.values()] + [n["x"] + n["w"] for n in nodes.values()]
    ys = [n["y"] for n in nodes.values()] + [n["y"] + n["h"] for n in nodes.values()]
    W, H = max(xs) - min(xs), max(ys) - min(ys)
    report["extent WxH"] = f"{W:.0f}x{H:.0f} (aspect {W / H:.2f})" if H else "?"
    view = data.get("view")
    if view and view.get("scale"):
        scale = view["scale"]
        report["default view scale"] = f"{scale:.2f} (fit would be {view['fit']:.2f}; stage {view['stageW']}x{view['stageH']})"
        report["on-screen edge label px"] = f"{13 * scale:.1f}"
        report["on-screen atom name px"] = f"{16 * scale:.1f}"
        report["on-screen atom subtitle px"] = f"{12 * scale:.1f}"
        vis = min(1.0, view["stageW"] / scale / W) * min(1.0, view["stageH"] / scale / H)
        report["drawing visible at default"] = f"{vis * 100:.0f}%"
    report["nodes/edges/labels"] = f"{len(pills)}/{len(edges)}/{len(labels)}"

    if "--json" in sys.argv:
        print(json.dumps(report))
        return
    width = max(len(k) for k in report)
    for k, v in report.items():
        print(f"{k.ljust(width)}  {v}")
    if "-v" in sys.argv:
        print()
        for f in findings:
            print(" ", f)


if __name__ == "__main__":
    main()
