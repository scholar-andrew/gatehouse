# Playing on October Journey

One download sets everything up: Prism Launcher, the modpack (it updates itself every time you play), the server in your
server list, and a Steam shortcut with its own library art and Steam Deck controller layout.

## Before you start
- **Minecraft Java Edition** on your Microsoft account.
- **Access to the server.** It's reached through Tailscale: install [Tailscale](https://tailscale.com/download), sign
  in, and accept the share the owner sends you. You also need to be on the server's whitelist; ask the owner.
- **Steam** (optional). Without it, setup still makes the Prism instance; you just play from Prism.

## Windows
1. Download [**OctoberJourneySetup.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneySetup.exe).
2. Run it. Windows may say "Windows protected your PC" because the file isn't code-signed: click **More info**, then
   **Run anyway**.
3. When it asks, quit Steam (Steam menu, then Exit) and click OK.
4. Start Steam, find **Minecraft (Prism Launcher) - your computer's name** in your library, and play. The first time
   Prism opens, sign in with your Microsoft account and pick **October Journey 26.3 (Fabric)**.

## Steam Deck (or Linux)
1. Switch to **Desktop Mode** and download
   [**october-journey-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-setup.sh).
2. Open **Konsole** in your Downloads folder and run:
   ```bash
   bash october-journey-setup.sh
   ```
   It installs Prism Launcher from Flathub if needed and asks before closing Steam.
3. Go back to Game Mode and play **Minecraft (Prism Launcher) - steamdeck** (or your device's name). Sign in to your
   Microsoft account the first time Prism opens.

## Staying up to date
Nothing to do: every launch checks for mod changes and installs them before the game starts. Running setup again is
safe; it updates your setup without duplicating anything.

## Controllers
The Steam Deck's own controls get the pack's layout (R4 jump, L4 crouch, trackpad as mouse). Other controllers work
through the Controlify mod; if you use one on a PC, turn off Steam Input for the shortcut (Properties, Controller).
