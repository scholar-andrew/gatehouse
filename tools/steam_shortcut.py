"""
Adds a non-Steam game shortcut to a Steam shortcuts.vdf (Steam must be closed, or it overwrites the file).
Usage: python steam_shortcut.py <shortcuts.vdf> <app name> <exe path> [launch options] [--replace <old name>]
Give the exe path bare (no quotes): the quotes Steam wants, the start folder and the icon are worked out here. A shortcut
with that name (or the --replace name) is updated in place; otherwise one is added. Writes <file>.bak-pack first.
"""
import binascii, os, shutil, struct, sys


def read_map(b, i):
    out = []
    while True:
        t = b[i]; i += 1
        if t == 0x08:
            return out, i
        j = b.index(0, i); key = b[i:j].decode("utf-8"); i = j + 1
        if t == 0x00:
            val, i = read_map(b, i)
        elif t == 0x01:
            j = b.index(0, i); val = b[i:j].decode("utf-8"); i = j + 1
        elif t == 0x02:
            val = struct.unpack("<i", b[i:i + 4])[0]; i += 4
        else:
            raise ValueError(f"unknown VDF type {t} at {i}")
        out.append((t, key, val))


def write_map(items):
    out = b""
    for t, key, val in items:
        out += bytes([t]) + key.encode("utf-8") + b"\x00"
        if t == 0x00:
            out += write_map(val)
        elif t == 0x01:
            out += val.encode("utf-8") + b"\x00"
        else:
            out += struct.pack("<i", val)
    return out + b"\x08"


def main():
    args = sys.argv[1:]
    old = None
    if "--replace" in args:
        i = args.index("--replace"); old = args[i + 1]; del args[i:i + 2]
    path, name, exe_path = args[:3]
    opts = args[3] if len(args) > 3 else ""
    exe = '"' + exe_path.strip('"') + '"'
    start = '"' + os.path.dirname(exe_path.strip('"')) + os.sep + '"'
    icon = exe_path.strip('"')
    if os.path.exists(path):
        b = open(path, "rb").read()
        root, _ = read_map(b, 0)
        shutil.copy2(path, path + ".bak-pack")
    else:
        root = [(0x00, "shortcuts", [])]
    shortcuts = next(v for t, k, v in root if k.lower() == "shortcuts")
    fields = {"AppName": name, "Exe": exe, "StartDir": start, "icon": icon, "LaunchOptions": opts}
    for _, _, entry in shortcuts:
        if any(k == "AppName" and v in (name, old) for _, k, v in entry):
            for n, (t, k, v) in enumerate(entry):
                if k in fields: entry[n] = (t, k, fields[k])
            with open(path, "wb") as f:
                f.write(write_map(root))
            read_map(open(path, "rb").read(), 0)
            print(f"updated {name}")
            return
    appid = (binascii.crc32((exe + name).encode("utf-8")) | 0x80000000) & 0xFFFFFFFF
    appid = struct.unpack("<i", struct.pack("<I", appid))[0]
    entry = [
        (0x02, "appid", appid), (0x01, "AppName", name), (0x01, "Exe", exe), (0x01, "StartDir", start),
        (0x01, "icon", icon), (0x01, "ShortcutPath", ""), (0x01, "LaunchOptions", opts), (0x02, "IsHidden", 0),
        (0x02, "AllowDesktopConfig", 1), (0x02, "AllowOverlay", 1), (0x02, "OpenVR", 0), (0x02, "Devkit", 0),
        (0x01, "DevkitGameID", ""), (0x02, "DevkitOverrideAppID", 0), (0x02, "LastPlayTime", 0),
        (0x01, "FlatpakAppID", ""), (0x00, "tags", []),
    ]
    shortcuts.append((0x00, str(len(shortcuts)), entry))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(write_map(root))
    # Read it back: the file must parse.
    read_map(open(path, "rb").read(), 0)
    print(f"added {name} ({len(shortcuts)} shortcut(s) now)")


if __name__ == "__main__":
    main()
