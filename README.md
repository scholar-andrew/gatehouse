# October Journey client pack

One modpack for every device that plays on the October Journey server, in packwiz format.

- `dist/main/pack.toml`: every device (PC, laptop).
- `dist/deck/pack.toml`: the same plus the Steam Deck-only mods (Controlify with YACL, chatlift, handofffix).
- `source/mods.json`: the list it is built from; `source/files/`: configs and jars that are not on Modrinth.
- `tools/build_pack.py`: rebuilds `dist/` from `source/`.
- `tools/serve.ps1`: serves `dist/` over Tailscale at http://100.91.134.102:8467/ (scheduled task "Minecraft pack server").
- `clients/`: what each device's Prism instance runs before launch. It tries this PC over Tailscale first, then GitHub,
  and picks the Deck pack on Steam Deck hardware (Jupiter, Galileo).
