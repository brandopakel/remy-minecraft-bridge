#!/usr/bin/env python3
"""Mock of the legacy GDMC-HTTP 0.4.x API that the Remy mod exposes, over a flat world.

Used to test agentcraft end-to-end without Minecraft. It mirrors the mod's contract:
  GET  /blocks?x&y&z            -> block id text
  PUT  /blocks?x&y&z            -> body "blockstate" or lines "~dx ~dy ~dz blockstate"; one "1"/"0" per line
  POST /command                 -> body: one command per line; returns one result line each
  GET  /buildarea               -> JSON {xFrom..zTo} or -1
  GET  /chunks?x&z&dx&dz        -> uncompressed NBT {Chunks:[{Level:{Heightmaps,Sections}}]} in 1.16 layout
Every request must carry X-Remy-Token when REMY_GDMC_TOKEN is set.
"""
import io
import math
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from nbt import nbt

TOKEN = os.environ.get("REMY_GDMC_TOKEN", "")
GROUND = 63
world = {}  # (x,y,z) -> block id; default terrain below
log = {"puts": 0, "commands": []}


def base_block(x, y, z):
    if y < 0:
        return "minecraft:air"
    if y == 0:
        return "minecraft:bedrock"
    if y < GROUND - 3:
        return "minecraft:stone"
    if y < GROUND:
        return "minecraft:dirt"
    if y == GROUND:
        return "minecraft:grass_block"
    if y == GROUND + 1 and (x * 7 + z * 13) % 97 == 0:
        return "minecraft:oak_log"
    return "minecraft:air"


def get_block(x, y, z):
    return world.get((x, y, z), base_block(x, y, z))


def pack(values, bits):
    per_long = 64 // bits
    longs = []
    for i in range(0, len(values), per_long):
        v = 0
        for j, val in enumerate(values[i:i + per_long]):
            v |= (val & ((1 << bits) - 1)) << (j * bits)
        if v >= 1 << 63:
            v -= 1 << 64
        longs.append(v)
    return longs


def heightmap(cx, cz):
    vals = []
    for z in range(16):
        for x in range(16):
            wx, wz = cx * 16 + x, cz * 16 + z
            h = 0
            for y in range(255, -1, -1):
                if get_block(wx, y, wz) != "minecraft:air":
                    h = y + 1
                    break
            vals.append(h)
    return pack(vals, 9)


def chunk_nbt(cx, cz):
    level = nbt.TAG_Compound(name="Level")
    hms = nbt.TAG_Compound(name="Heightmaps")
    hm = heightmap(cx, cz)
    for name in ["MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE"]:
        t = nbt.TAG_Long_Array(name=name)
        t.value = hm
        hms.tags.append(t)
    level.tags.append(hms)
    sections = nbt.TAG_List(name="Sections", type=nbt.TAG_Compound)
    for sy in range(16):
        palette, idx = [], {}
        states = []
        for y in range(16):
            for z in range(16):
                for x in range(16):
                    b = get_block(cx * 16 + x, sy * 16 + y, cz * 16 + z)
                    if b not in idx:
                        idx[b] = len(palette)
                        palette.append(b)
                    states.append(idx[b])
        sec = nbt.TAG_Compound()
        sec.tags.append(nbt.TAG_Byte(name="Y", value=sy))
        pal = nbt.TAG_List(name="Palette", type=nbt.TAG_Compound)
        for b in palette:
            c = nbt.TAG_Compound()
            c.tags.append(nbt.TAG_String(name="Name", value=b))
            pal.tags.append(c)
        sec.tags.append(pal)
        bits = max(4, math.ceil(math.log2(len(palette)))) if len(palette) > 1 else 4
        bs = nbt.TAG_Long_Array(name="BlockStates")
        bs.value = pack(states, bits)
        sec.tags.append(bs)
        sections.tags.append(sec)
    level.tags.append(sections)
    chunk = nbt.TAG_Compound()
    chunk.tags.append(level)
    return chunk


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _ok(self, body, ctype="text/plain"):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _auth(self):
        if self.headers.get("Origin"):
            self.send_error(403, "browser origins are not allowed")
            return False
        if TOKEN and self.headers.get("X-Remy-Token") != TOKEN:
            self.send_error(401, "missing or wrong X-Remy-Token")
            return False
        return True

    def _q(self):
        u = urlparse(self.path)
        return u.path, {k: v[0] for k, v in parse_qs(u.query).items()}

    def do_GET(self):
        if not self._auth():
            return
        path, q = self._q()
        if path == "/blocks":
            return self._ok(get_block(int(q["x"]), int(q["y"]), int(q["z"])))
        if path == "/buildarea":
            return self._ok('{"xFrom":0,"yFrom":0,"zFrom":0,"xTo":64,"yTo":255,"zTo":64}', "application/json")
        if path == "/chunks":
            x, z, dx, dz = (int(q[k]) for k in ("x", "z", "dx", "dz"))
            root = nbt.NBTFile()
            root.name = ""
            chunks = nbt.TAG_List(name="Chunks", type=nbt.TAG_Compound)
            for cz in range(z, z + dz):
                for cx in range(x, x + dx):
                    chunks.tags.append(chunk_nbt(cx, cz))
            root.tags.append(chunks)
            buf = io.BytesIO()
            root.write_file(buffer=buf)
            return self._ok(buf.getvalue(), "application/octet-stream")
        self.send_error(404)

    def do_PUT(self):
        if not self._auth():
            return
        path, q = self._q()
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        ox, oy, oz = int(q.get("x", 0)), int(q.get("y", 0)), int(q.get("z", 0))
        out = []
        for line in body.splitlines() or [body]:
            parts = line.split()
            if len(parts) >= 4:
                x, y, z = (int(p.lstrip("~") or 0) + o if p.startswith("~") else int(p) for p, o in zip(parts[:3], (ox, oy, oz)))
                block = " ".join(parts[3:])
            else:
                x, y, z, block = ox, oy, oz, line.strip()
            world[(x, y, z)] = block.split("[")[0].split("{")[0]
            log["puts"] += 1
            out.append("1")
        self._ok("\n".join(out))

    def do_POST(self):
        if not self._auth():
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        cmds = [c for c in body.splitlines() if c.strip()]
        log["commands"].extend(cmds)
        self._ok("\n".join("1" for _ in cmds))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock GDMC on 127.0.0.1:{port}", flush=True)
    try:
        srv.serve_forever()
    finally:
        print(f"blocks placed: {log['puts']}, commands: {len(log['commands'])}", flush=True)
