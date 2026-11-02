"""
Blender turntable renderer for the home-screen 3D cars (Forester SG5 / SH and the other Sketchfab models;
render_models.sh renders all passes of those and packs them with pack_frames.py).

  Blender -b -P render_turntable.py -- --model car.glb --out frames/ [--list] [--frames 90]
      [--paint name1,=exact_name] [--plate name] [--engine EEVEE|CYCLES] [--size 1100x620] [--still]
      [--fix sg|sgna|sh|shna|sk|grc|bm|va|gg|gv|22b|gc|gt3r] [--paintmode chameleon|white|mask] [--only 0,9,43] [--probe]

--list   only prints materials (name, users, face count) so paint/plate materials can be picked.
--probe  (with --fix) prints what sits at the tail and the bumper surfaces, to place tailpipes and plates.
Body paint gets an "eggplant chameleon" shader: hue shifts with viewing angle
(deep eggplant -> violet -> green -> bronze on grazing angles), metallic flakes + clear coat.
Frames are rendered with a transparent background; the app draws its own backdrop.
"""
import argparse
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
ap = argparse.ArgumentParser()
ap.add_argument("--model", required=True)
ap.add_argument("--out", default="frames")
ap.add_argument("--list", action="store_true")
ap.add_argument("--frames", type=int, default=90)
ap.add_argument("--paint", default="")
ap.add_argument("--plate", default="")
ap.add_argument("--plate_image", default=os.path.join(os.path.dirname(__file__), "plate_sg5.png"))
ap.add_argument("--engine", default="EEVEE")
ap.add_argument("--size", default="1100x620")
ap.add_argument("--samples", type=int, default=64)
ap.add_argument("--still", action="store_true", help="render only frame 0 (preview)")
ap.add_argument("--only", default="", help="render only these frame numbers, e.g. 0,9,43 (preview)")
ap.add_argument("--elev", type=float, default=12.0, help="camera elevation, degrees")
ap.add_argument("--envelope", default="", help="xhalf,yhalf,zmin,zmax: drop meshes outside")
ap.add_argument("--start", type=float, default=-35.0, help="first frame camera azimuth, degrees")
ap.add_argument("--tail", type=float, default=180.0, help="camera azimuth that looks at the tail (drive view)")
ap.add_argument("--fix", default="", help="model-specific clean-up: sg, sgna, sh, shna, sk, grc, bm, va, gg, gv, 22b, gc, gt3r")
ap.add_argument("--probe", action="store_true", help="after --fix: print tail/plate area objects and exit")
ap.add_argument("--probe_near", default="", help="with --probe: also list meshes near points 'x,y,z,r;x,y,z,r'")
ap.add_argument("--wheelz", type=float, default=0.43, help="sg: wheel centre height (default: the mod's hub height)")
ap.add_argument("--origfront", action="store_true", help="sg: keep the aftermarket front bumper the mod has fitted")
ap.add_argument("--altfront", action="store_true", help="(old option, the stock front is now the default)")
ap.add_argument("--floor", action="store_true", help="add a shadow-catcher floor (Cycles only)")
ap.add_argument("--paintmode", default="chameleon",
                help="chameleon | white (neutral body the app tints to any colour) | mask (body white, rest black)")
args = ap.parse_args(argv)

# ---------- scene ----------
bpy.ops.wm.read_factory_settings(use_empty=True)
ext = os.path.splitext(args.model)[1].lower()
if ext in (".glb", ".gltf"):
    bpy.ops.import_scene.gltf(filepath=args.model)
elif ext == ".fbx":
    bpy.ops.import_scene.fbx(filepath=args.model)
elif ext == ".obj":
    bpy.ops.wm.obj_import(filepath=args.model)
else:
    raise SystemExit("unsupported model format " + ext)

meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]

# This model (BeamNG-style mod) has spare parts laid out around the car: keep only what sits
# inside the car's envelope (median-centred), drop the rest.
if args.envelope:
    ex, ey, ez0, ez1 = (float(v) for v in args.envelope.split(","))
    removed = []
    for o in list(meshes):
        bb = [o.matrix_world @ Vector(c) for c in o.bound_box]
        if (min(p.x for p in bb) < -ex or max(p.x for p in bb) > ex or
                min(p.y for p in bb) < -ey or max(p.y for p in bb) > ey or
                min(p.z for p in bb) < ez0 or max(p.z for p in bb) > ez1):
            removed.append(o.name)
            bpy.data.objects.remove(o, do_unlink=True)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    print("REMOVED", len(removed), removed[:40])

if args.list:
    stats = {}
    for o in meshes:
        for p in o.data.polygons:
            m = o.material_slots[p.material_index].material if o.material_slots else None
            n = m.name if m else "<none>"
            stats[n] = stats.get(n, 0) + 1
    for n, c in sorted(stats.items(), key=lambda x: -x[1]):
        print("MAT", c, n)
    raise SystemExit(0)


def plate_material(path):
    m = bpy.data.materials.new("PlateKG")
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(path)
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.45
    return m


def fix_sg_model():
    """Clean-up for the szymonpasterczyk 'Subaru Forester MK2 2002-2008' (BeamNG-style mod) model: metres,
    front at -Y. The mod keeps spare parts around the car (bonnet, a second front end, wheel templates,
    engines, an interior far above), has no window glass at all (only the black frit rings and a
    cracked-glass line mesh) and a race interior."""
    FIX_PAINT["sg"] = ["body_froster.001"]
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}

    def bbox(o):
        bb = [o.matrix_world @ Vector(c) for c in o.bound_box]
        return Vector([min(p[i] for p in bb) for i in range(3)]), Vector([max(p[i] for p in bb) for i in range(3)])

    def drop(o):
        bpy.data.objects.remove(o, do_unlink=True)

    # the body is symmetric about x = cx (not 0)
    bmn, bmx = bbox(objs["body_froster-material.004"])
    cx = (bmn.x + bmx.x) / 2
    BACKFACE_DARK[args.fix] = (cx - 0.95, cx + 0.95, -0.87, 1.72, 0.30, 1.56)  # (front wings are flipped)
    # 1) bonnet: parked to the side; centre it on the body (it is already at the right length and height)
    hood = objs.get("body_froster-material.007")
    keep = {hood}
    if hood:
        mn, mx = bbox(hood)
        hood.matrix_world.translation.x += cx - (mn.x + mx.x) / 2
    # 2) front end: the one on the car is an aftermarket bumper (big round fogs, open mesh); the stock 2006+
    # bumper with grille and small fogs is parked ~2.3 m to the left. Swap unless --origfront.
    if not args.origfront:
        for o in list(objs.values()):  # current front bumper parts that the parked set replaces
            mn, mx = bbox(o)
            if o is not hood and mx.y < -1.6 and mn.z < 0.85 and abs((mn.x + mx.x) / 2 - cx) < 0.6 \
                    and (mx.x - mn.x) > 1.2 and mn.x > -1.3:
                print("REPLACED", o.name)
                drop(o)
        objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
        alt = [o for o in objs.values() if bbox(o)[1].x < -1.3 and bbox(o)[0].y < -1.6
               and bbox(o)[1].z < 1.0 and bbox(o)[0].z > 0.1 and o is not hood]
        # the parked set also has a copy of the grille (with its badge), parked off-centre: the car's own
        # grille stays, the copy goes
        dup = ("plastic_froster-material.018", "chrome_froster-material.006", "blue_froster-material.001")
        for o in [o for o in alt if o.name in dup]:
            alt.remove(o)
            drop(o)
        objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
        if alt:
            # centre on the body via the bumper skin (the widest part)
            skin = max(alt, key=lambda o: bbox(o)[1].x - bbox(o)[0].x)
            mn, mx = bbox(skin)
            dx = cx - (mn.x + mx.x) / 2
            for o in alt:
                o.matrix_world.translation.x += dx
                keep.add(o)
                print("ALTFRONT", o.name)
    # 3) spare parts, roof light bar, helper meshes, plates, the race cage
    for o in list(objs.values()):
        if o.name not in bpy.context.scene.objects or o in keep:
            continue
        mn, mx = bbox(o)
        c = (mn + mx) / 2
        name = o.name
        floating_plate = mx.y < -2.25 and mn.z > 1.15
        roof_junk = name in ("engine_mesh-material", "plastic_froster-material.012") or (mn.z > 1.44 and len(o.data.polygons) < 100)
        stray = mx.z < 0.15 or mn.z > 1.75 or abs(c.x - cx) > 1.15 or (mx.z < 0.2 and len(o.data.polygons) < 200)
        template = name in ("wheel_tire__CLR_4_-material", "rim-material.001")
        helper = name.startswith("Object_") or name.startswith("glass_invisible-material.002") or any(
            sl.material and "skelet" in sl.material.name.lower() for sl in o.material_slots)
        old_plate = name.startswith(("Material__1-material", "Material__2-material", "Material__3-material",
                                     "Material__4-material"))
        old_hub = name.startswith("wheel_tire__CLR_4_-material.") or name in ("rim-material", "rubber_interior2-material")
        if (floating_plate or roof_junk or stray or helper or old_plate or old_hub) and not template:
            drop(o)
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
    # 3b) empty the cabin of the mod's race interior (seats, cage, harnesses): whatever lies fully inside the
    # cabin volume and is neither body nor the windscreen frit
    for o in list(objs.values()):
        mn, mx = bbox(o)
        mats = " ".join(sl.material.name.lower() for sl in o.material_slots if sl.material)
        inside = mn.x > cx - 0.86 and mx.x < cx + 0.86 and mn.y > -1.15 and mx.y < 1.75 and mn.z > 0.30 and mx.z < 1.47
        if inside and "body_froster" not in mats and o.name != "plastic_froster-material.014":
            drop(o)
    objs = {o.name: o for o in bpy.context.scene.objects if o.type == "MESH"}
    # 4) wheels from the tyre + rim templates at the mod's hub positions (brake discs: x +-0.78, y -1.40 /
    # +1.09, z 0.33), scaled to a stock-size tyre (0.65 m) so the car stands on them at stock height
    rubber = new_material("Rubber", (0.012, 0.012, 0.014), rough=0.85)
    tire = objs.get("wheel_tire__CLR_4_-material")
    rim = objs.get("rim-material.001")
    hubs = []
    for o in objs.values():  # the disc meshes sit at the hubs
        if o.name.startswith("etk800-material.0") and len(o.data.polygons) == 122:
            mn, mx = bbox(o)
            hubs.append(((mn + mx) / 2))
    centres = {}
    for h in hubs:
        k = (h.x > cx, h.y > 0)
        centres.setdefault(k, []).append(h)
    centres = [sum(v, Vector()) / len(v) for v in centres.values()] if len(centres) == 4 else \
        [Vector((cx + sx * 0.78, y, 0.33)) for sx in (-1, 1) for y in (-1.40, 1.09)]
    wheel_z = args.wheelz if args.wheelz != 0.43 else sum(c.z for c in centres) / len(centres)
    tmn, tmx = bbox(tire)
    # the tyre template is a whole BeamNG wheel with its own simple 5-spoke rim inside; with the separate
    # rim mesh on top that read as a wheel within a wheel. Keep only the tyre (faces away from the axle).
    tc0 = (tmn + tmx) / 2
    r_cut = 0.74 * (tmx.z - tmn.z) / 2
    drop_faces(lambda o, c, m: o is tire and math.hypot(c.y - tc0.y, c.z - tc0.z) < r_cut, [tire])
    scale = 0.65 / (tmx.z - tmn.z)
    for tmpl in (tire, rim):
        mn, mx = bbox(tmpl)
        tc = (mn + mx) / 2
        for k, c in enumerate(centres):
            o = tmpl.copy()
            o.data = tmpl.data
            bpy.context.scene.collection.objects.link(o)
            # move to the origin, scale (a bit narrower than uniform), mirror the left side, put on the hub
            m = Matrix.Translation(-tc) @ tmpl.matrix_world
            m = Matrix.Diagonal((scale * 0.92, scale, scale, 1)) @ m
            if (c.x - cx) * (tc.x - cx) < 0:  # template is on the other side: mirror in X
                m = Matrix.Scale(-1, 4, (1, 0, 0)) @ m
            o.matrix_world = Matrix.Translation(Vector((c.x, c.y, wheel_z))) @ m
            if tmpl is tire:
                for slot in o.material_slots:
                    slot.material = rubber
            bpy.context.view_layer.update()
            mn3, mx3 = bbox(o)
            print("WHEEL", "tire" if tmpl is tire else "rim", k, tuple(round(v, 3) for v in mn3), tuple(round(v, 3) for v in mx3))
        drop(tmpl)
    # the rims' grey plastic look (the wide dish read as a white-wall tyre): polished alloy
    alloy = bpy.data.materials.get("material")
    if alloy and alloy.use_nodes and "Principled BSDF" in alloy.node_tree.nodes:
        b = alloy.node_tree.nodes["Principled BSDF"]
        b.inputs["Base Color"].default_value = (0.55, 0.56, 0.58, 1)
        b.inputs["Metallic"].default_value = 1.0
        b.inputs["Roughness"].default_value = 0.32
    # the mod's chassis parts (wheel-arch liners, discs, subframes) are light grey and glow in the arches
    chassis = bpy.data.materials.get("etk800")
    if chassis and chassis.use_nodes and "Principled BSDF" in chassis.node_tree.nodes:
        chassis.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.055, 1)
    # 5) lime-yellow fog lamp lenses -> clear lens
    fog = new_material("ClearLens", (0.85, 0.87, 0.9), rough=0.05, coat=1.0)
    for o in scene_meshes():
        for slot in o.material_slots:
            if slot.material and "FFB9FF00" in slot.material.name:
                slot.material = fog
    # 6) window glass: the mod has none. Windscreen and tailgate glass fill the inner edge of their frit
    # rings; the side windows are found by looking through the car from the side.
    glass = tinted_glass()
    ring = bpy.context.scene.objects.get("plastic_froster-material.014")
    if ring:
        fill_ring_glass(ring, glass)
    for side in (1, -1):
        side_glass(side, cx, glass, y0=-1.20, y1=1.75, z0=0.88, z1=1.50)
    # 7) cabin: the inner door/roof skins are body paint; darken whatever paint faces the inside of the cabin
    darken_inner_paint(cx, "body_froster.001")
    darken_arch_paint("body_froster.001", [Vector((c.x, c.y, wheel_z)) for c in centres])
    # 8) blank plates on the bumper and the tailgate; tailpipe (right rear)
    plate_on_surface("PlateFront", 0.50, -1, x=cx)
    plate_on_surface("PlateRear", 0.795, 1, x=cx)
    exhausts_from_faces(faces_where(by_obj("chrome_froster-material.013")))


def fill_ring_glass(ring, mat, inset=0.004):
    """Glass panes for a frit ring object: fill each inner boundary loop (of the pairs outer/inner)."""
    import bmesh
    bm = bmesh.new()
    bm.from_mesh(ring.data)
    bm.transform(ring.matrix_world)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=0.0005)
    bnd = [e for e in bm.edges if len(e.link_faces) == 1]
    adj = {}
    for e in bnd:
        for v in e.verts:
            adj.setdefault(v, []).append(e)
    seen, loops = set(), []
    for e in bnd:
        if e in seen:
            continue
        comp, stack = [], [e]
        while stack:
            x = stack.pop()
            if x in seen:
                continue
            seen.add(x)
            comp.append(x)
            for v in x.verts:
                stack.extend(adj[v])
        loops.append(comp)
    # order loop vertices; pair loops by centre, the shorter of each pair is the inner one
    def ordered(comp):
        vs = {v for x in comp for v in x.verts}
        start = next(iter(vs))
        order, prev, cur = [start], None, start
        while True:
            nxt = [w for x in adj[cur] if x in comp for w in x.verts if w is not cur and w is not prev]
            if not nxt or nxt[0] is start:
                break
            prev, cur = cur, nxt[0]
            order.append(cur)
            if len(order) > len(vs):
                break
        return [v.co.copy() for v in order]
    info = []
    for comp in loops:
        pts = ordered(comp)
        c = sum(pts, Vector()) / len(pts)
        L = sum(x.calc_length() for x in comp)
        info.append((L, c, pts))
    bm.free()
    info.sort(key=lambda t: -t[0])
    used = set()
    for i, (L, c, pts) in enumerate(info):
        if i in used:
            continue
        # partner: the nearest other loop centre
        j = min((k for k in range(len(info)) if k != i and k not in used),
                key=lambda k: (info[k][1] - c).length, default=None)
        if j is None or (info[j][1] - c).length > 0.2:
            continue
        used.update((i, j))
        inner = info[j] if info[j][0] < L else info[i]
        pts = inner[2]
        me = bpy.data.meshes.new("Glass")
        nb = bmesh.new()
        vs = [nb.verts.new(p) for p in pts]
        f = nb.faces.new(vs)
        bmesh.ops.triangulate(nb, faces=[f], quad_method="BEAUTY", ngon_method="BEAUTY")
        # push it a few mm into the car (away from the outside, i.e. towards the loop centre's cabin side)
        cen = sum(pts, Vector()) / len(pts)
        n = Vector()
        for a, b in zip(pts, pts[1:] + pts[:1]):
            n += (a - cen).cross(b - cen)
        n.normalize()
        if n.dot(Vector((0, 0, 1))) < 0:  # make it point up/out
            n = -n
        for v in nb.verts:
            v.co -= n * inset
        nb.to_mesh(me)
        nb.free()
        o = bpy.data.objects.new("GlassPane", me)
        bpy.context.scene.collection.objects.link(o)
        me.materials.append(mat)
        print("GLASS ring pane", len(pts), "verts at", tuple(round(v, 2) for v in cen))


def side_glass(side, cx, mat, y0, y1, z0, z1, step=0.01, deep=0.45, inset=0.006):
    """Side window panes for a model without glass: look at the car from the side (side +1: from +X) on a
    grid; where the first hit is deep inside the car (or nothing), there is a window opening. Each opening
    gets a pane on the plane fitted to the surrounding frame, grown by two cells so it tucks under it."""
    import bmesh
    import numpy as np
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    sc = bpy.context.scene
    ny, nz = int((y1 - y0) / step) + 1, int((z1 - z0) / step) + 1
    hitx = np.full((ny, nz), np.nan)
    for i in range(ny):
        for j in range(nz):
            y, z = y0 + i * step, z0 + j * step
            ok, loc, _, _, _, _ = sc.ray_cast(dg, Vector((cx + side * 3, y, z)), Vector((-side, 0, 0)))
            if ok:
                hitx[i, j] = (loc.x - cx) * side
    open_ = np.isnan(hitx) | (hitx < deep)
    # the openings must be closed above (roof) and below (door): only cells with frame both above and below
    lab = np.zeros((ny, nz), int)
    comps = []
    for i in range(ny):
        for j in range(nz):
            if open_[i, j] and not lab[i, j]:
                n = len(comps) + 1
                stack, cells = [(i, j)], []
                lab[i, j] = n
                while stack:
                    a, b = stack.pop()
                    cells.append((a, b))
                    for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        u, w = a + da, b + db
                        if 0 <= u < ny and 0 <= w < nz and open_[u, w] and not lab[u, w]:
                            lab[u, w] = n
                            stack.append((u, w))
                comps.append(cells)
    made = 0
    for n, cells in enumerate(comps, 1):
        if len(cells) < 60:
            continue
        ii = [c[0] for c in cells]
        jj = [c[1] for c in cells]
        if min(ii) == 0 or max(ii) == ny - 1 or min(jj) == 0 or max(jj) == nz - 1:
            continue  # touches the grid edge: not a window (front/rear of the car, above the roof)
        cellset = set(cells)
        grown = set(cellset)
        for _ in range(2):
            grown |= {(a + da, b + db) for a, b in grown for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1))
                      if 0 <= a + da < ny and 0 <= b + db < nz}
        ring = [(a, b) for a, b in grown - cellset if not np.isnan(hitx[a, b]) and hitx[a, b] > deep]
        if len(ring) < 10:
            continue
        P = np.array([(y0 + a * step, z0 + b * step, hitx[a, b]) for a, b in ring])
        A = np.c_[np.ones(len(P)), P[:, 0], P[:, 1]]
        coef, *_ = np.linalg.lstsq(A, P[:, 2], rcond=None)
        res = P[:, 2] - A @ coef
        keepm = abs(res) < max(0.01, 2.5 * res.std())  # drop mirror/handle hits and refit
        coef, *_ = np.linalg.lstsq(A[keepm], P[keepm, 2], rcond=None)
        me = bpy.data.meshes.new("SideGlass")
        bm = bmesh.new()
        verts = {}

        def vert(a, b):
            if (a, b) not in verts:
                y, z = y0 + (a - 0.5) * step, z0 + (b - 0.5) * step
                x = cx + side * (coef[0] + coef[1] * y + coef[2] * z - inset)
                verts[(a, b)] = bm.verts.new((x, y, z))
            return verts[(a, b)]
        for a, b in grown:
            q = [vert(a, b), vert(a + 1, b), vert(a + 1, b + 1), vert(a, b + 1)]
            bm.faces.new(q if side > 0 else q[::-1])
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        for f in bm.faces:
            if f.normal.x * side < 0:
                f.normal_flip()
        bm.to_mesh(me)
        bm.free()
        o = bpy.data.objects.new("SideGlass", me)
        sc.collection.objects.link(o)
        me.materials.append(mat)
        made += 1
        print("GLASS side %+d pane %d cells, y %.2f..%.2f z %.2f..%.2f, plane x=%.3f%+.3fy%+.3fz" % (
            side, len(cells), y0 + min(ii) * step, y0 + max(ii) * step, z0 + min(jj) * step, z0 + max(jj) * step,
            coef[0], coef[1], coef[2]))
    print("GLASS side", side, "panes", made)


def darken_arch_paint(paint, centres, radius=0.46):
    """Painted wheel-arch liners (faces around a wheel that look towards its axle) -> dark."""
    dark = dark_cabin()
    n = 0
    for o in scene_meshes():
        slots = [i for i, sl in enumerate(o.material_slots) if sl.material and sl.material.name == paint]
        if not slots:
            continue
        mw = o.matrix_world
        vs = o.data.vertices
        hit = []
        for p in o.data.polygons:
            if p.material_index not in slots:
                continue
            pts = [mw @ vs[v].co for v in p.vertices]
            c = sum(pts, Vector()) / len(pts)
            nrm = Vector()
            for a, b in zip(pts, pts[1:] + pts[:1]):
                nrm += a.cross(b)
            if nrm.length == 0:
                continue
            nrm.normalize()
            for w in centres:
                r = Vector((0, c.y - w.y, c.z - w.z))
                if r.length < radius and abs(c.x - w.x) < 0.25 and r.length > 0.2 \
                        and nrm.dot(r.normalized()) < -0.4:
                    hit.append(p.index)
                    break
        if hit:
            if o.data.users > 1:
                o.data = o.data.copy()
            o.data.materials.append(dark)
            k = len(o.data.materials) - 1
            for i in hit:
                o.data.polygons[i].material_index = k
            n += len(hit)
    print("DARKENED arch liner faces", n)


def darken_inner_paint(cx, paint, box=(0.86, -1.15, 1.75, 0.25, 1.52)):
    """Paint faces inside the cabin that face its inside (door cards, roof lining) -> dark cabin material,
    so they do not show body colour through the glass."""
    dark = dark_cabin()
    hx, y0, y1, z0, z1 = box
    n = 0
    for o in scene_meshes():
        slots = [i for i, sl in enumerate(o.material_slots) if sl.material and sl.material.name == paint]
        if not slots:
            continue
        mw = o.matrix_world
        nm = mw.to_3x3().inverted_safe().transposed()
        vs = o.data.vertices
        flip = []
        for p in o.data.polygons:
            if p.material_index not in slots:
                continue
            pts = [mw @ vs[v].co for v in p.vertices]
            c = sum(pts, Vector()) / len(pts)
            if not (abs(c.x - cx) < hx and y0 < c.y < y1 and z0 < c.z < z1):
                continue
            nrm = Vector()
            for a, b in zip(pts, pts[1:] + pts[:1]):
                nrm += a.cross(b)
            if nrm.length == 0:
                continue
            nrm.normalize()
            inward_side = abs(c.x - cx) > 0.45 and nrm.x * math.copysign(1, c.x - cx) < -0.35
            roof_lining = nrm.z < -0.6 and c.z > 1.2
            if inward_side or roof_lining:
                flip.append(p.index)
        if flip:
            if o.data.users > 1:
                o.data = o.data.copy()
            o.data.materials.append(dark)
            k = len(o.data.materials) - 1
            for i in flip:
                o.data.polygons[i].material_index = k
            n += len(flip)
    print("DARKENED inner paint faces", n)


def fix_sgna_model():
    """The SG5 without the bonnet scoop (non-turbo look): the scoop shell and its insert are separate
    meshes; the slot under them is closed with a bonnet patch."""
    fix_sg_model()
    FIX_PAINT["sgna"] = FIX_PAINT["sg"]
    drop_objects(lambda o: o.name in ("body_froster-material.012", "plastic_froster-material.009"))
    # the bonnet has a slot under the scoop: flatten its edge and close it with painted bonnet
    flatten_bulge(["body_froster-material.007"], -0.25, 0.28, -1.65, -1.35,
                  patch_mat=bpy.data.materials["body_froster.001"])


def fix_sh_model():
    """Clean-up for niev's 'Subaru Forester 2009' (SH, XT) model: length along X, front at small X."""
    # The model has no window glass, so the light cabin shows through. Make the cabin near-black instead:
    # the windows then read as tinted, like on the SG frames.
    dark = bpy.data.materials.new("DarkCabin")
    dark.use_nodes = True
    dk = dark.node_tree.nodes["Principled BSDF"]
    dk.inputs["Base Color"].default_value = (0.006, 0.006, 0.008, 1)
    dk.inputs["Roughness"].default_value = 0.7
    cabin = {"Interior_1", "Interior_2", "Interior_3", "Leather_1", "Upholstery", "Carpet", "Wood_Trim",
             "LCD_Screen", "Tinted_Glass"}
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for slot in o.material_slots:
                if slot.material and slot.material.name in cabin:
                    slot.material = dark
    # blank plates (the app draws the user's plate on them using the exported corners)
    blank = bpy.data.materials.new("PlateBlank")
    blank.use_nodes = True
    bb = blank.node_tree.nodes["Principled BSDF"]
    bb.inputs["Base Color"].default_value = (0.9, 0.9, 0.9, 1)
    bb.inputs["Roughness"].default_value = 0.5
    # rear: flat tailgate recess at z 0.70-0.80; front: lower bumper, centre line y = 1.0625
    for name, loc, ry in (("PlateFront", (1.528, 1.0625, 0.50), -90), ("PlateRear", (5.962, 1.0625, 0.755), 90)):
        bpy.ops.mesh.primitive_plane_add(size=1, location=loc, rotation=(0, math.radians(ry), 0))
        pl = bpy.context.active_object
        pl.name = name
        pl.scale = (0.113, 0.52, 1)
        pl.data.materials.append(blank)

# ---------- helpers for the Sketchfab models below ----------
# Every fix_<id>_model() leaves the car in metres, Z up, front towards -Y, centred, wheels on z = 0
# (the root transform below then turns it so the front is at +X, as with the SG). It also creates
# PlateFront / PlateRear blank quads where the car has plates and ExhaustTip* empties at the tailpipes.

def scene_meshes():
    return [o for o in bpy.context.scene.objects if o.type == "MESH"]


def world_bbox(objs=None):
    bpy.context.view_layer.update()
    objs = scene_meshes() if objs is None else objs
    pts = [o.matrix_world @ Vector(c) for o in objs for c in o.bound_box]
    return (Vector([min(p[i] for p in pts) for i in range(3)]),
            Vector([max(p[i] for p in pts) for i in range(3)]))


def mat_names(o):
    return [sl.material.name for sl in o.material_slots if sl.material]


def drop_objects(pred):
    """Remove meshes for which pred(obj) is true; returns the removed names."""
    gone = []
    for o in list(scene_meshes()):
        if pred(o):
            gone.append(o.name)
            bpy.data.objects.remove(o, do_unlink=True)
    print("DROPPED", len(gone), gone[:30])
    return gone


def drop_faces(pred, objs=None):
    """Delete faces for which pred(obj, world_centre, material_name) is true (meshes made single-user first)."""
    import bmesh
    n = 0
    for o in list(scene_meshes() if objs is None else objs):
        mw = o.matrix_world
        mats = [sl.material.name if sl.material else "" for sl in o.material_slots]
        vs = o.data.vertices
        hit = [p.index for p in o.data.polygons
               if pred(o, face_centre(mw, vs, p), mats[p.material_index] if p.material_index < len(mats) else "")]
        if not hit:
            continue
        if len(hit) == len(o.data.polygons):
            bpy.data.objects.remove(o, do_unlink=True)
            n += len(hit)
            continue
        if o.data.users > 1:
            o.data = o.data.copy()
        bm = bmesh.new()
        bm.from_mesh(o.data)
        bm.faces.ensure_lookup_table()
        bmesh.ops.delete(bm, geom=[bm.faces[i] for i in hit], context="FACES")
        bm.to_mesh(o.data)
        bm.free()
        n += len(hit)
    print("DROPPED_FACES", n)


def bake_modifiers():
    """Apply modifiers (e.g. the armature skinning of game exports) so mesh data = what is rendered."""
    dg = bpy.context.evaluated_depsgraph_get()
    n = 0
    for o in scene_meshes():
        if o.modifiers:
            me = bpy.data.meshes.new_from_object(o.evaluated_get(dg), preserve_all_data_layers=True, depsgraph=dg)
            o.modifiers.clear()
            o.data = me
            n += 1
    print("BAKED", n)


def normalise(length, yaw=0.0):
    """Scale the whole model so its length (along Y after turning it by yaw degrees) is `length` metres
    (None: keep the scale), centre it on x/y and stand it on z = 0."""
    norm = bpy.data.objects.new("Norm", None)
    bpy.context.scene.collection.objects.link(norm)
    for o in list(bpy.context.scene.objects):
        if o.parent is None and o is not norm:
            o.parent = norm
    norm.rotation_euler.z = math.radians(yaw)
    mn, mx = world_bbox()
    s = length / (mx.y - mn.y) if length else 1.0
    norm.scale = (s, s, s)
    mn, mx = world_bbox()
    norm.location = (-(mn.x + mx.x) / 2, -(mn.y + mx.y) / 2, -mn.z)
    mn, mx = world_bbox()
    print("NORMALISED scale %.5f" % s, tuple(round(v, 3) for v in mn), tuple(round(v, 3) for v in mx))


def new_material(name, rgb, rough=0.5, metal=0.0, alpha=1.0, coat=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*rgb, 1)
    b.inputs["Roughness"].default_value = rough
    b.inputs["Metallic"].default_value = metal
    b.inputs["Coat Weight"].default_value = coat
    if alpha < 1:
        b.inputs["Alpha"].default_value = alpha
        try:
            m.surface_render_method = "BLENDED"
        except Exception:
            m.blend_method = "BLEND"
    return m


def replace_materials(names, mat, exact=True):
    """Swap every slot whose material name is in names (or contains one of them when exact=False)."""
    low = [n.lower() for n in names]
    for o in scene_meshes():
        for sl in o.material_slots:
            if not sl.material:
                continue
            n = sl.material.name.lower()
            if (n in low) if exact else any(x in n for x in low):
                sl.material = mat


def rename_material(old, new):
    m = bpy.data.materials.get(old)
    if m:
        m.name = new
    return m


def tinted_glass(alpha=0.7):
    m = new_material("TintGlass", (0.01, 0.012, 0.016), rough=0.03, alpha=alpha)
    # weak reflections: the big top light otherwise turns windscreens milky in the front views
    m.node_tree.nodes["Principled BSDF"].inputs["Specular IOR Level"].default_value = 0.0
    return m


def dark_cabin():
    return new_material("DarkCabin", (0.012, 0.012, 0.014), rough=0.7)


def darken_cabin(prefixes):
    """Cabin materials -> near-black, so the windows read as tinted (as on the SG/SH frames)."""
    dark = dark_cabin()
    low = tuple(p.lower() for p in prefixes)
    for o in scene_meshes():
        for sl in o.material_slots:
            if sl.material and sl.material.name.lower().startswith(low):
                sl.material = dark


def plate_blank():
    m = bpy.data.materials.get("PlateBlank")
    return m or new_material("PlateBlank", (0.9, 0.9, 0.9), rough=0.5)


def faces_where(pred):
    """World-space (centre, normal, area, verts) of every face for which pred(obj, material_name) is true."""
    out = []
    for o in scene_meshes():
        mw = o.matrix_world
        mats = [sl.material.name if sl.material else "" for sl in o.material_slots]
        for p in o.data.polygons:
            mname = mats[p.material_index] if p.material_index < len(mats) else ""
            if pred(o, mname):
                vs = [mw @ o.data.vertices[v].co for v in p.vertices]
                # Newell normal; its length is twice the area
                n = Vector()
                for a, b in zip(vs, vs[1:] + vs[:1]):
                    n += a.cross(b)
                out.append((sum(vs, Vector()) / len(vs), n.normalized(), n.length / 2, vs))
    return out


def face_centre(mw, verts, p):
    # (MeshPolygon.center is not reliable on some imported meshes, so average the vertices)
    return mw @ (sum((verts[v].co for v in p.vertices), Vector()) / len(p.vertices))


def add_plate(name, centre, normal, width, lift=0.004):
    """Blank plate quad (EU proportions 520x113) centred at `centre`, facing `normal`, upright."""
    n = Vector(normal).normalized()
    up = Vector((0, 0, 1))
    y = (up - n * up.dot(n)).normalized()
    x = y.cross(n)
    hgt = width * 0.113 / 0.52
    c = Vector(centre) + n * lift
    m = Matrix((
        (x.x * width, y.x * hgt, n.x, c.x),
        (x.y * width, y.y * hgt, n.y, c.y),
        (x.z * width, y.z * hgt, n.z, c.z),
        (0, 0, 0, 1)))
    bpy.ops.mesh.primitive_plane_add(size=1)
    pl = bpy.context.active_object
    pl.name = name
    pl.matrix_world = m
    pl.data.materials.append(plate_blank())
    print("PLATE", name, tuple(round(v, 3) for v in c), "normal", tuple(round(v, 2) for v in n), "w", round(width, 3))
    return pl


def plates_from_faces(faces, max_width=0.52, min_width=0.0, drop=True, lift=0.004):
    """Blank plates over the model's own plate faces: front = faces at y < 0, rear = y > 0. The plate keeps
    the EU proportions; its width is the model plate's width (clamped)."""
    for name, side in (("PlateFront", -1), ("PlateRear", 1)):
        fs = [f for f in faces if f[0].y * side > 0 and f[1].y * side > 0.3]
        if not fs:
            continue
        area = sum(f[2] for f in fs) or 1.0
        nrm = sum((f[1] * f[2] for f in fs), Vector()).normalized()
        vs = [v for f in fs for v in f[3]]
        mn = Vector([min(v[i] for v in vs) for i in range(3)])
        mx = Vector([max(v[i] for v in vs) for i in range(3)])
        c = (mn + mx) / 2
        # put the quad on the outermost surface of the plate
        c.y = mn.y if side < 0 else mx.y
        w = max(min_width, min(max_width, mx.x - mn.x))
        add_plate(name, c, nrm, w, lift)


def surface_point(x, z, side, objs=None):
    """First hit when looking at the car along Y from the front (side -1) or the rear (side 1)."""
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    ok, loc, nrm, idx, hit, _ = bpy.context.scene.ray_cast(dg, Vector((x, side * 50, z)), Vector((0, -side, 0)))
    return (loc, nrm, hit.name) if ok else (None, None, None)


def plate_on_surface(name, z, side, width=0.52, x=0.0, lift=0.006):
    """Blank plate on the bumper/boot surface found by a ray along Y at height z. The plate stands upright
    (only the bumper's left-right tilt is ignored) so that the text is not sheared."""
    loc, nrm, hit = surface_point(x, z, side)
    if loc is None:
        print("PLATE_MISS", name)
        return None
    n = Vector((0, nrm.y, nrm.z)).normalized()
    if n.y * side < 0.5:
        n = Vector((0, side, 0))
    print("PLATE_HIT", name, hit)
    return add_plate(name, loc, n, width, lift)


def add_exhausts(points):
    for k, p in enumerate(points):
        e = bpy.data.objects.new("ExhaustTip%d" % k, None)
        e.location = p
        bpy.context.scene.collection.objects.link(e)
    print("EXHAUSTS", [tuple(round(v, 3) for v in p) for p in points])


def exhausts_from_faces(faces, depth=0.03):
    """Tailpipe ends: rear-most vertices of the given faces, one point per side of the car."""
    pts = []
    for side in (-1, 1):
        vs = [v for f in faces for v in f[3] if v.x * side > 0.05]
        if not vs:
            continue
        ymax = max(v.y for v in vs)
        tip = [v for v in vs if v.y > ymax - depth]
        pts.append(sum(tip, Vector()) / len(tip))
    if not pts:  # a single centre pipe
        vs = [v for f in faces for v in f[3]]
        if vs:
            ymax = max(v.y for v in vs)
            tip = [v for v in vs if v.y > ymax - depth]
            pts.append(sum(tip, Vector()) / len(tip))
    add_exhausts(pts)


def in_box(faces, y0=-99, y1=99, z0=-99, z1=99, ax0=0, ax1=99):
    """Faces whose centre lies in the box (ax = |x|)."""
    return [f for f in faces if y0 <= f[0].y <= y1 and z0 <= f[0].z <= z1 and ax0 <= abs(f[0].x) <= ax1]


def by_mat(*names):
    low = [n.lower() for n in names]
    return lambda o, m: m.lower() in low


def by_obj(*subs):
    return lambda o, m: any(s in o.name for s in subs)


# paint materials (exact names, after the fix renamed them) per model
FIX_PAINT = {}
# models whose painted shell is single-sided (inside seen through the glass): cabin box for dark_backfaces
BACKFACE_DARK = {}


def fix_sk_model():
    """tonielpro520's '2020 Subaru Forester' (SK): inches, Y = length, front at -Y."""
    FIX_PAINT["sk"] = ["M_2020_Subaru_Forester_Brilliant_Bronze_Metallic"]
    plate_faces = faces_where(by_mat("Philippine_License_Plate_Recovered"))
    normalise(4.64)
    plate_faces = faces_where(by_mat("Philippine_License_Plate_Recovered"))
    plates_from_faces(plate_faces, max_width=0.50)
    drop_faces(lambda o, c, m: m == "Philippine_License_Plate_Recovered")
    exhausts_from_faces(in_box(faces_where(by_mat("M_2020_Subaru_Forester_BlackChrome")), y0=1.8, z1=0.5, ax0=0.3))
    replace_materials(["M_2020_Subaru_Forester_Windows"], tinted_glass())
    # every material of this export is fully rough; give the obvious ones some shine back
    for n, rough, metal in (("ChromeTrims", 0.12, 1.0), ("BlackChrome", 0.15, 1.0), ("Mirrors", 0.05, 1.0),
                            ("Rims1", 0.25, 1.0), ("BlackTrims", 0.55, 0.0), ("Grille1", 0.35, 0.0),
                            ("HeadLightsGlass", 0.02, 0.0), ("BrakeLightsGlass", 0.02, 0.0),
                            ("Tires", 0.85, 0.0), ("WindowsTrims", 0.4, 0.0), ("Rims2", 0.3, 0.6),
                            ("SubaruLogo", 0.2, 0.6), ("DRLs", 0.2, 0.0), ("Red1", 0.2, 0.0), ("RedMain", 0.2, 0.0),
                            ("Orange1", 0.2, 0.0), ("Reflectors", 0.2, 0.0)):
        m = bpy.data.materials.get("M_2020_Subaru_Forester_" + n)
        if m and m.use_nodes and "Principled BSDF" in m.node_tree.nodes:
            b = m.node_tree.nodes["Principled BSDF"]
            b.inputs["Roughness"].default_value = rough
            b.inputs["Metallic"].default_value = metal


def fix_grc_model():
    """amogusstrikesback2's 'subaru wrx sti grc' (game asset, metres, front at -Y). The livery is baked into
    the body texture; the paint passes replace it with plain paint."""
    FIX_PAINT["grc"] = ["Material.001"]
    # light glow cards (the headlight beams stick out 1.2 m in front of the car)
    drop_objects(lambda o: "_glows_" in o.name.lower())
    normalise(None)
    drop_faces(lambda o, c, m: m == "Material.001" and c.z > 1.50)  # roof antenna
    replace_materials(["glasses_glass__0"], tinted_glass())
    plates_from_faces(faces_where(by_mat("Material.005")), max_width=0.40)  # rear only, a race car
    drop_objects(lambda o: "Material.005" in mat_names(o))
    add_exhausts([Vector((-0.53, 2.30, 0.36)), Vector((0.53, 2.30, 0.36))])  # tips under the bumper corners


def fix_bm_model():
    """ilvskf's '2010 Subaru Legacy 2.5GT tS (BM9)': metres, front at -Y, no plates."""
    FIX_PAINT["bm"] = ["body_car_paint"]
    normalise(None)
    replace_materials(["Glass"], tinted_glass())
    darken_cabin(["int_", "streeing", "shift_", "supra_interior", "button", "lukparts", "green", "material_78",
                  "material_79"])
    exhausts_from_faces(faces_where(by_mat("ext_chrome_exhaust")))
    plate_on_surface("PlateFront", 0.43, -1)
    plate_on_surface("PlateRear", 0.77, 1)


def fix_va_model():
    """OUTPISTON's '2018 Subaru WRX STi' (VA): metres, front at -Y; a unit icosphere without material
    (an environment helper) is centred on the car."""
    FIX_PAINT["va"] = ["Paint"]
    drop_objects(lambda o: o.name.startswith("Icosphere") or not o.material_slots)
    bake_modifiers()  # skinned export: the mesh data is in bind pose
    normalise(None)
    replace_materials(["GlassMtl"], tinted_glass())
    darken_cabin(["InteriorA", "InteriorColour1A", "InteriorTillingA"])
    exhausts_from_faces(in_box(faces_where(by_mat("Coloured")), y0=1.95, z1=0.4, ax0=0.38))
    plates_from_faces(faces_where(by_mat("ManufacturerPlateA")), max_width=0.52)  # rear: a Subaru dealer plate
    drop_objects(lambda o: "ManufacturerPlateA" in mat_names(o))
    plate_on_surface("PlateFront", 0.45, -1)


def fix_gg_model():
    """ilvskf's 'Subaru WRX Wagon (GG)': metres, front at -Y, no plates."""
    FIX_PAINT["gg"] = ["car_paint"]
    normalise(None)
    replace_materials(["Glass", "Glass_Normal"], tinted_glass())
    exhausts_from_faces([f for f in in_box(faces_where(by_mat("Underside")), y0=2.0, ax0=0.2) if f[0].x > 0])
    plate_on_surface("PlateFront", 0.44, -1)
    plate_on_surface("PlateRear", 0.45, 1)


def fix_gv_model():
    """amogusstrikesback2's 'subaru impreza wrx sti special edition' (game asset, metres, front at -Y). Despite
    the file name this is the 2011-2014 GV sedan (wide-body kit). The itasha livery is in the body texture and
    a decal mesh; both go."""
    # paint = doors, rear quarters, boot (no_alpha.007) and the roof panel (no_alpha.006); the wide-body
    # kit, bumpers and the carbon bonnet share the black-trim material/atlas and stay black
    FIX_PAINT["gv"] = ["no_alpha.007", "no_alpha.006"]
    drop_objects(lambda o: "_glows_" in o.name.lower() or "decals" in o.name.lower())
    normalise(None)
    replace_materials(["Material.010"], tinted_glass())
    plates_from_faces(faces_where(by_mat("Material.009")), max_width=0.52)
    drop_objects(lambda o: "Material.009" in mat_names(o))
    plate_on_surface("PlateFront", 0.47, -1)
    exhausts_from_faces(faces_where(by_obj("chassis_rims_")))


def fix_22b_model():
    """VTX's '1998 Subaru Impreza 22B STi': exported 1:100, front at -Y, no plates."""
    FIX_PAINT["22b"] = ["material"]
    normalise(4.365)
    replace_materials(["windiws"], tinted_glass())
    exhausts_from_faces(faces_where(by_obj("Object_84")))
    plate_on_surface("PlateFront", 0.47, -1, width=0.40)
    plate_on_surface("PlateRear", 0.40, 1, width=0.40)


def fix_gc_model():
    """vecarz's 'Subaru Impreza Coupe WRX Type R STi' (GC8 coupe, Assetto Corsa style): metres, front at -Y,
    JDM plates."""
    FIX_PAINT["gc"] = ["ext_body"]
    normalise(None)
    plates_from_faces(faces_where(by_mat("ext_plate_front", "ext_plate_rear")), max_width=0.40)
    drop_objects(lambda o: any(m in ("ext_plate_front", "ext_plate_rear", "ext_plate_screws") for m in mat_names(o)))
    exhausts_from_faces(in_box(faces_where(by_obj("Object_22")), y0=1.7))
    # the windscreen texture renders as an opaque mint film
    replace_materials(["int_windshield", "int_windschield_tinted", "ext_glass_tint", "ext_rear_tint_dark"],
                      tinted_glass())


def fix_gd_model():
    """'Generic Toyama XR Sedan', an Impreza GD look-alike (2003-05 front): metres, front at -Y. A ground
    shadow card sits under the car; the rear plate is part of the shared 'Textures' atlas."""
    FIX_PAINT["gd"] = ["Body_Paint"]
    drop_objects(lambda o: "Shadow" in mat_names(o))
    normalise(None)
    replace_materials(["Body_Glass"], tinted_glass())
    # one tailpipe, on the right under the bumper
    exhausts_from_faces(in_box(faces_where(by_mat("Body_Chrome")), y0=1.9, z1=0.4))
    plate_on_surface("PlateFront", 0.36, -1)  # flat recess in the lower bumper
    plate_on_surface("PlateRear", 0.53, 1)  # over the model's own plate


def fix_gt3r_model():
    """'2024 Porsche 992 GT3 R' by Dave Love: the stand-in for a car nobody can name. Its own livery stays: no paint material. Front at -Y, 1:100 scale, no plates."""
    normalise(4.62)
    drop_objects(lambda o: any(m.startswith("EXT_RIM_BLUR") for m in mat_names(o)))
    # the file's paint is a mirror: the big area lights came back off it as flat white shapes over the livery; only the gloss changes
    b = bpy.data.materials["EXT_Carpaint_Inst"].node_tree.nodes["Principled BSDF"]
    b.inputs["Roughness"].default_value = 0.25
    b.inputs["Metallic"].default_value = 0.0
    b.inputs["Specular IOR Level"].default_value = 0.1
    add_exhausts([Vector((-0.06, 2.15, 0.29)), Vector((0.06, 2.15, 0.29))])


def flatten_bulge(obj_names, x0, x1, y0, y1, ring=0.16, feather=0.08, other_drop=True, patch_mat=None):
    """Remove a bonnet scoop that is modelled into the bonnet mesh: fit a smooth (quadratic) surface to the
    bonnet top around the box [x0..x1] x [y0..y1] (world, z up), pull every bonnet vertex in the box down/up
    onto it (feathered over `feather` m outside the box), delete other objects' faces that would stick out
    of the new surface there (scoop grille), and put a painted patch just under the surface to close the
    scoop opening."""
    import numpy as np
    objs = [bpy.context.scene.objects[n] for n in obj_names]

    def dist_out(x, y):  # distance outside the box (0 inside)
        dx = max(x0 - x, 0, x - x1)
        dy = max(y0 - y, 0, y - y1)
        return math.hypot(dx, dy)

    pts = []
    for o in objs:
        mw = o.matrix_world
        n3 = mw.to_3x3().inverted_safe().transposed()
        for v in o.data.vertices:
            p = mw @ v.co
            d = dist_out(p.x, p.y)
            if feather < d < ring and (n3 @ v.normal).normalized().z > 0.6:
                pts.append((p.x, p.y, p.z))
    a = np.array(pts)
    A = np.c_[np.ones(len(a)), a[:, 0], a[:, 1], a[:, 0] ** 2, a[:, 1] ** 2, a[:, 0] * a[:, 1]]
    coef, *_ = np.linalg.lstsq(A, a[:, 2], rcond=None)
    res = a[:, 2] - A @ coef
    print("BULGE fit from", len(a), "points, residual rms %.4f max %.4f" % (np.sqrt((res ** 2).mean()), abs(res).max()))

    def surf(x, y):
        return float(coef @ np.array([1, x, y, x * x, y * y, x * y]))

    moved = 0
    for o in objs:
        if o.data.users > 1:
            o.data = o.data.copy()
        mw = o.matrix_world
        inv = mw.inverted_safe()
        for v in o.data.vertices:
            p = mw @ v.co
            d = dist_out(p.x, p.y)
            if d >= feather:
                continue
            s = surf(p.x, p.y)
            if p.z < s - 0.12:  # underside / engine bay, leave alone
                continue
            w = 1.0 - d / feather if d > 0 else 1.0
            p.z = p.z + (s - p.z) * w
            v.co = inv @ p
            moved += 1
        o.data.update()
    print("BULGE flattened", moved, "vertices")
    if patch_mat is not None:  # (built before any face is dropped: wanted() looks for bonnet under it)
        import bmesh
        me = bpy.data.meshes.new("BonnetPatch")
        bm = bmesh.new()
        n = 32
        e = feather * 0.75  # past the box: bonnet faces crossing its edge get dropped whole below
        grid = [[bm.verts.new((x0 - e + (x1 - x0 + 2 * e) * i / n, y0 - e + (y1 - y0 + 2 * e) * j / n, 0))
                 for j in range(n + 1)] for i in range(n + 1)]
        for row in grid:
            for v in row:
                v.co.z = surf(v.co.x, v.co.y) - 0.0025
        bpy.context.view_layer.update()
        dg = bpy.context.evaluated_depsgraph_get()

        def wanted(x, y):  # past the box only where there is bonnet right under it (not over the cowl gap)
            if x0 <= x <= x1 and y0 <= y <= y1:
                return True
            ok, loc, _, _, hit, _ = bpy.context.scene.ray_cast(dg, Vector((x, y, surf(x, y) + 0.1)), Vector((0, 0, -1)))
            return ok and hit.name in obj_names and abs(loc.z - surf(x, y)) < 0.02

        for i in range(n):
            for j in range(n):
                q = (grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
                if all(wanted(v.co.x, v.co.y) for v in q):
                    bm.faces.new(q)
        bmesh.ops.delete(bm, geom=[v for v in bm.verts if not v.link_faces], context="VERTS")
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        for f in bm.faces:
            if f.normal.z < 0:
                f.normal_flip()
            f.smooth = True
        bm.to_mesh(me)
        bm.free()
        po = bpy.data.objects.new("BonnetPatch", me)
        bpy.context.scene.collection.objects.link(po)
        me.materials.append(patch_mat)
    if patch_mat is not None:
        # inside the box the collapsed scoop walls keep their imported (custom) normals and shade as streaks:
        # drop the bonnet faces there, the patch below shows instead
        drop_faces(lambda o, c, m: x0 < c.x < x1 and y0 < c.y < y1 and c.z > surf(c.x, c.y) - 0.12, objs)
    if other_drop:
        # other parts (scoop insert, bonnet underside following the bulge) whose faces reach the new surface
        import bmesh
        keep = set(obj_names) | {"BonnetPatch"}
        n = 0
        for o in list(scene_meshes()):
            if o.name in keep:
                continue
            mw = o.matrix_world
            wv = [mw @ v.co for v in o.data.vertices]
            hit = [p.index for p in o.data.polygons
                   if any(dist_out(wv[i].x, wv[i].y) < 0.02 and wv[i].z > surf(wv[i].x, wv[i].y) - 0.012
                          for i in p.vertices)]
            if not hit:
                continue
            if o.data.users > 1:
                o.data = o.data.copy()
            bm = bmesh.new()
            bm.from_mesh(o.data)
            bm.faces.ensure_lookup_table()
            bmesh.ops.delete(bm, geom=[bm.faces[i] for i in hit], context="FACES")
            bm.to_mesh(o.data)
            bm.free()
            n += len(hit)
        print("BULGE dropped", n, "faces of other parts")


def fix_shna_model():
    """The SH without the XT bonnet scoop (non-turbo look). The scoop is part of the bonnet mesh (Object_24):
    flatten it onto the bonnet surface, drop its black insert, close the opening with painted bonnet; the XT
    badge goes too."""
    fix_sh_model()
    FIX_PAINT["shna"] = ["Body_Color"]
    flatten_bulge(["Object_24"], 2.00, 2.66, 0.70, 1.42, patch_mat=bpy.data.materials["Body_Color"])
    # and the "XT" letters under the FORESTER badge on the tailgate
    drop_faces(lambda o, c, m: m == "Chrome_02" and c.x > 5.85 and 1.53 < c.y < 1.78 and 0.92 < c.z < 0.977)


FIXES = {"sk": fix_sk_model, "grc": fix_grc_model, "bm": fix_bm_model, "va": fix_va_model, "gg": fix_gg_model,
         "gv": fix_gv_model, "22b": fix_22b_model, "gc": fix_gc_model, "gd": fix_gd_model, "shna": fix_shna_model,
         "sg": fix_sg_model, "sgna": fix_sgna_model, "gt3r": fix_gt3r_model}

if args.fix == 'sh':
    fix_sh_model()
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
elif args.fix in FIXES:
    FIXES[args.fix]()
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    if not args.paint:
        args.paint = ",".join("=" + n for n in FIX_PAINT.get(args.fix, []))

if args.probe:
    # after the fix: what sits at the tail below the bumper line (tailpipes) and at the plate spots
    bmn, bmx = world_bbox()
    print("BBOX", tuple(round(v, 3) for v in bmn), tuple(round(v, 3) for v in bmx))
    for spec in [t for t in args.probe_near.split(";") if t]:
        px, py, pz, pr = (float(v) for v in spec.split(","))
        for o in scene_meshes():
            a, b = world_bbox([o])
            d = Vector((max(a.x - px, 0, px - b.x), max(a.y - py, 0, py - b.y), max(a.z - pz, 0, pz - b.z))).length
            if d < pr:
                print("NEAR", spec, o.name, len(o.data.polygons), mat_names(o)[:3],
                      tuple(round(v, 3) for v in a), tuple(round(v, 3) for v in b))
    for o in scene_meshes():
        a, b = world_bbox([o])
        if b.y > bmx.y - 0.35 and a.z < 0.5 and (b.x - a.x) < 1.6:
            print("REAR_LOW", o.name, mat_names(o)[:4], tuple(round(v, 3) for v in a), tuple(round(v, 3) for v in b))
    tail = {}
    for o in scene_meshes():
        mw = o.matrix_world
        mats = [sl.material.name if sl.material else "" for sl in o.material_slots]
        for p in o.data.polygons:
            c = face_centre(mw, o.data.vertices, p)
            if c.y > bmx.y - 0.45 and c.z < 0.5 and abs(c.x) > 0.2:
                k = (o.name, mats[p.material_index] if p.material_index < len(mats) else "", c.x > 0)
                t = tail.setdefault(k, [Vector((9, 9, 9)), Vector((-9, -9, -9)), 0])
                for i in range(3):
                    t[0][i] = min(t[0][i], c[i]); t[1][i] = max(t[1][i], c[i])
                t[2] += 1
    for k, (a, b, cnt) in sorted(tail.items(), key=lambda kv: -kv[1][1].y)[:25]:
        print("TAIL", k, cnt, tuple(round(v, 3) for v in a), tuple(round(v, 3) for v in b))
    for side in (-1, 1):
        for z in [0.25 + 0.05 * k for k in range(18)]:
            loc, nrm, hit = surface_point(0.0, z, side)
            if loc is not None:
                o = bpy.context.scene.objects[hit]
                print("SURFACE", "front" if side < 0 else "rear", round(z, 2), round(loc.y, 3),
                      tuple(round(v, 2) for v in nrm), hit, mat_names(o)[:2])
    raise SystemExit(0)

# Normalise: put the car on the ground, centred, longest axis = X.
root = bpy.data.objects.new("CarRoot", None)
bpy.context.scene.collection.objects.link(root)
for o in bpy.context.scene.objects:
    if o.parent is None and o is not root:
        o.parent = root
bpy.context.view_layer.update()
pts = [o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
mn = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
mx = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
size = mx - mn
centre = (mn + mx) / 2
if size.y > size.x:  # make the car's length run along X
    root.rotation_euler.z = math.radians(90)
# the location applies after the rotation: move the rotated centre to the origin (the plain -centre left the
# SG5, whose model is not centred, ~15 cm to one side in the frames)
root.location = -(Matrix.Rotation(root.rotation_euler.z, 3, "Z") @ Vector((centre.x, centre.y, 0))) + Vector((0, 0, -mn.z))
length = max(size.x, size.y)
height = size.z

# ---------- materials ----------
def chameleon_material():
    m = bpy.data.materials.new("EggplantChameleon")
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    lw = nt.nodes.new("ShaderNodeLayerWeight")
    lw.inputs["Blend"].default_value = 0.55
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    cr = ramp.color_ramp
    cr.interpolation = "B_SPLINE"
    cr.elements[0].position = 0.0
    cr.elements[0].color = (0.10, 0.012, 0.16, 1)    # eggplant (facing the viewer)
    cr.elements[1].position = 0.38
    cr.elements[1].color = (0.30, 0.03, 0.42, 1)     # saturated violet
    e = cr.elements.new(0.62)
    e.color = (0.02, 0.30, 0.20, 1)                  # emerald shift
    e = cr.elements.new(0.85)
    e.color = (0.55, 0.38, 0.05, 1)                  # gold on grazing angles
    nt.links.new(lw.outputs["Facing"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Metallic"].default_value = 0.85
    bsdf.inputs["Roughness"].default_value = 0.32
    bsdf.inputs["Coat Weight"].default_value = 1.0
    bsdf.inputs["Coat Roughness"].default_value = 0.03
    # metallic flake: tiny noise into roughness
    noise = nt.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 900.0
    mapr = nt.nodes.new("ShaderNodeMapRange")
    mapr.inputs["To Min"].default_value = 0.22
    mapr.inputs["To Max"].default_value = 0.42
    nt.links.new(noise.outputs["Fac"], mapr.inputs["Value"])
    nt.links.new(mapr.outputs["Result"], bsdf.inputs["Roughness"])
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    return m




def white_material():
    m = bpy.data.materials.new("WhitePaint")
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (0.82, 0.82, 0.82, 1)
    b.inputs["Metallic"].default_value = 0.0
    b.inputs["Roughness"].default_value = 0.28
    b.inputs["Coat Weight"].default_value = 1.0
    b.inputs["Coat Roughness"].default_value = 0.03
    return m

def emission(name, rgb):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*rgb, 1)
    em.inputs["Strength"].default_value = 1.0
    nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    return m

paint_names = [s.strip().lower() for s in args.paint.split(",") if s.strip()]
paint = {"white": white_material, "mask": lambda: emission("MaskBody", (1, 1, 1))}.get(args.paintmode, chameleon_material)()


def dark_backfaces(m, box):
    """Single-skin bodies (the SG mod's doors and roof) show the paint from inside through the windows: shade
    back faces inside the cabin box (model coordinates x0,x1,y0,y1,z0,z1, before the root transform) as dark
    cabin, black in the mask. Outside the box back faces stay painted: some panels have flipped normals."""
    bpy.context.view_layer.update()
    corners = [root.matrix_world @ Vector((x, y, z)) for x in box[0:2] for y in box[2:4] for z in box[4:6]]
    lo = [min(c[i] for c in corners) for i in range(3)]
    hi = [max(c[i] for c in corners) for i in range(3)]
    nt = m.node_tree
    out = next(n for n in nt.nodes if n.type == "OUTPUT_MATERIAL")
    src = out.inputs["Surface"].links[0].from_socket
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(geo.outputs["Position"], sep.inputs[0])
    fac = geo.outputs["Backfacing"]
    for i, axis in enumerate("XYZ"):
        for bound, op in ((lo[i], "GREATER_THAN"), (hi[i], "LESS_THAN")):
            cmp = nt.nodes.new("ShaderNodeMath")
            cmp.operation = op
            nt.links.new(sep.outputs[axis], cmp.inputs[0])
            cmp.inputs[1].default_value = bound
            mul = nt.nodes.new("ShaderNodeMath")
            mul.operation = "MULTIPLY"
            nt.links.new(fac, mul.inputs[0])
            nt.links.new(cmp.outputs[0], mul.inputs[1])
            fac = mul.outputs[0]
    mix = nt.nodes.new("ShaderNodeMixShader")
    if args.paintmode == "mask":
        back = nt.nodes.new("ShaderNodeEmission")
        back.inputs["Color"].default_value = (0, 0, 0, 1)
    else:
        back = nt.nodes.new("ShaderNodeBsdfDiffuse")
        back.inputs["Color"].default_value = (0.012, 0.012, 0.014, 1)
    nt.links.new(fac, mix.inputs["Fac"])
    nt.links.new(src, mix.inputs[1])
    nt.links.new(back.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])


if args.fix in BACKFACE_DARK:
    dark_backfaces(paint, BACKFACE_DARK[args.fix])
mask_other = emission("MaskOther", (0, 0, 0)) if args.paintmode == "mask" else None
plate = plate_material(args.plate_image) if args.plate and os.path.exists(args.plate_image) else None
for o in meshes:
    for slot in o.material_slots:
        if not slot.material:
            continue
        n = slot.material.name.lower()
        # "=name" matches the material name exactly, a bare name matches any material containing it
        if any((n == p[1:]) if p.startswith("=") else (p in n) for p in paint_names):
            slot.material = paint
        elif plate and args.plate.lower() in n:
            slot.material = plate
        elif mask_other:
            slot.material = mask_other
if mask_other:  # plates and anything without a material are not body either
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for slot in o.material_slots:
                if slot.material is None or slot.material.name == "PlateBlank":
                    slot.material = mask_other

# ---------- lights / world ----------
scene = bpy.context.scene
world = bpy.data.worlds.new("World")
scene.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
bg.inputs["Color"].default_value = (0.02, 0.012, 0.05, 1)
bg.inputs["Strength"].default_value = 0.6


def area(name, loc, size_xy, energy, color):
    d = bpy.data.lights.new(name, "AREA")
    d.energy = energy
    d.size = size_xy[0]
    d.shape = "RECTANGLE"
    d.size_y = size_xy[1]
    d.color = color
    o = bpy.data.objects.new(name, d)
    o.location = loc
    scene.collection.objects.link(o)
    direction = Vector((0, 0, height * 0.4)) - Vector(loc)
    o.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    return o


L = length
area("Top", (0, 0, height * 3.2), (L * 1.6, L * 0.8), 900 * L * L / 20, (1.0, 0.97, 1.0))
area("KeyL", (-L * 1.2, -L * 1.1, height * 1.4), (L * 0.9, L * 0.4), 450 * L * L / 20, (0.80, 0.70, 1.0))
area("KeyR", (L * 1.2, -L * 1.0, height * 1.2), (L * 0.9, L * 0.4), 380 * L * L / 20, (0.65, 0.85, 1.0))
area("Rim", (0, L * 1.6, height * 1.8), (L * 1.4, L * 0.4), 600 * L * L / 20, (0.75, 0.45, 1.0))

if args.floor:
    bpy.ops.mesh.primitive_plane_add(size=L * 6, location=(0, 0, 0))
    floor = bpy.context.active_object
    floor.is_shadow_catcher = True

# ---------- camera ----------
cam_d = bpy.data.cameras.new("Cam")
cam_d.lens = 55
cam = bpy.data.objects.new("Cam", cam_d)
scene.collection.objects.link(cam)
scene.camera = cam
dist = L * 2.15
elev = math.radians(args.elev)
target = Vector((0, 0, height * 0.42))

# ---------- render settings ----------
w, h = (int(x) for x in args.size.split("x"))
scene.render.resolution_x = w
scene.render.resolution_y = h
scene.render.film_transparent = True
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Medium High Contrast"
if args.paintmode == "mask":  # exact 0/1 values
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
if args.engine.upper() == "CYCLES":
    scene.render.engine = "CYCLES"
    scene.cycles.samples = args.samples
    scene.cycles.use_denoising = True
    prefs = bpy.context.preferences.addons["cycles"].preferences
    for t in ("METAL", "NONE"):
        try:
            prefs.compute_device_type = t
            prefs.get_devices()
            for dv in prefs.devices:
                dv.use = True
            scene.cycles.device = "GPU" if t != "NONE" else "CPU"
            break
        except Exception:
            continue
else:
    scene.render.engine = "BLENDER_EEVEE_NEXT"
    ee = scene.eevee
    ee.taa_render_samples = args.samples
    try:
        ee.use_raytracing = True
        ee.use_shadows = True
    except Exception:
        pass

os.makedirs(args.out, exist_ok=True)
n = 1 if args.still else args.frames
from bpy_extras.object_utils import world_to_camera_view
import json
plates_out = []


def plate_quads():
    """Screen corners (x right, y down, 0..1) of each plate if it faces the camera and isn't hidden."""
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    res = {}
    for name in ("PlateFront", "PlateRear"):
        o = scene.objects.get(name)
        if not o:
            continue
        mw = o.matrix_world
        corners = [mw @ Vector(c) for c in ((-0.5, -0.5, 0), (0.5, -0.5, 0), (0.5, 0.5, 0), (-0.5, 0.5, 0))]
        centre = sum(corners, Vector()) / 4
        normal = (mw.to_3x3() @ Vector((0, 0, 1))).normalized()
        to_cam = (cam.location - centre).normalized()
        if normal.dot(to_cam) < 0.15:
            continue
        ok, loc, nrm, idx, hit, mat = scene.ray_cast(dg, cam.location, (centre - cam.location).normalized())
        if ok and hit.name != name and (loc - cam.location).length < (centre - cam.location).length - 0.02:
            continue
        # order: top-left, top-right, bottom-right, bottom-left as seen in the image; "top" = the two
        # corners that are higher in the world (sorting by image y breaks on steep views of sloped plates)
        corners.sort(key=lambda c: -c.z)
        pts = []
        for c in corners:
            v = world_to_camera_view(scene, cam, c)
            pts.append([round(v.x, 5), round(1 - v.y, 5)])
        top = sorted(pts[:2]); bot = sorted(pts[2:])
        res["front" if name == "PlateFront" else "rear"] = [top[0], top[1], bot[1], bot[0]]
    return res

def place_camera(i):
    # 0 = 3/4 front view, then rotate the camera around the car
    a = math.radians(args.start) + 2 * math.pi * i / args.frames
    cam.location = Vector((math.cos(a) * dist * math.cos(elev), math.sin(a) * dist * math.cos(elev), target.z + dist * math.sin(elev)))
    cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
    bpy.context.view_layer.update()


# the app's driving view: the frame looking at the tail (camera azimuth 180 = on -X, for cars with the front
# at +X like the SG and the --fix models; the SH is built the other way round: --tail 0)
drive_frame = int(round((args.tail - args.start) / 360.0 * args.frames)) % args.frames
place_camera(drive_frame)
tips = sorted((o for o in scene.objects if o.name.startswith("ExhaustTip")), key=lambda o: o.matrix_world.translation.y)
exhausts = []
for o in tips:
    v = world_to_camera_view(scene, cam, o.matrix_world.translation)
    exhausts.append([round(v.x, 5), round(1 - v.y, 5)])

only = [int(v) for v in args.only.split(",") if v.strip()]
for i in range(n):
    if only and i not in only:
        plates_out.append({})
        continue
    place_camera(i)
    scene.render.filepath = os.path.join(args.out, "car_%03d.png" % i)
    plates_out.append(plate_quads())
    bpy.ops.render.render(write_still=True)
    print("FRAME", i + 1, "/", n, flush=True)

with open(os.path.join(args.out, "plates.json"), "w") as f:
    json.dump(plates_out, f)
# uncropped screen positions (0..1); pack_frames.py turns them into meta.json for the cropped frames
with open(os.path.join(args.out, "render_meta.json"), "w") as f:
    json.dump({"driveFrame": drive_frame, "exhausts": exhausts, "size": [w, h]}, f)
