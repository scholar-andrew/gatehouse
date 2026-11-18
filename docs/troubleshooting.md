# Troubleshooting

Start with the program's own checks: in Gatehouse, a server's **Connection** tab has a network check that names the
first thing in the way; a Gatekeeper that isn't answering has **Connect**, which shows the missing step. In
Gatekeeper, **Host setup** lists every check at once.

## Players

| What you see | What to do |
| --- | --- |
| A Gatekeeper says it isn't answering | Press **Connect**: Tailscale not running or signed in on this device, the host's invite not accepted, or the host's machine asleep or off. |
| **Update needed** instead of Play | Your Gatehouse or the host's Gatekeeper is older than the oldest version still supported. The line under the heading says which; update that one. |
| A **Request** button instead of Play | You aren't on the whitelist, the server is off and you may not start it, or you've been suspended. Press it to ask the host. |
| The update stops with a message on the very first launch | No pack source answered and no mods are installed yet, so the game would not be able to join. Check Tailscale, then press Play again. |
| The update says offline, but you can play | Nothing answered; the game starts with what is installed. It catches up next time. |
| The mods never install | The game folder's `minecraft/pack-sync.log` and `pack-sync-java.log` say why; send them to the host. |
| A switched-off mod is still on | Switches apply when the update runs: press **Restart**, then Play. |
| The controller doesn't move anything (Windows) | XInput needs Java 22 or newer: the Java the game uses. Prism Launcher fetches it for Minecraft 26. |
| The Steam keyboard doesn't open (Windows) | Press Steam + X in a text box; Steam ignores the game's own request there. |

## Hosts

| What you see | What to do |
| --- | --- |
| A new player's launcher never appears under Players | Their device has to reach your machine over Tailscale: check they accepted your invite, and on Windows that the firewall lets Gatekeeper's Java in on private networks. Host setup's last check says whether any launcher has ever got through. |
| The server won't start from a launcher | **Host setup**. Windows with MCSS: MCSS found, its web API on, and Gatekeeper signed in to MCSS. A server Gatekeeper runs: its folder needs its program and `eula=true`; if it starts and stops again, the server's last lines are in `host.log` (often the wrong Java or too little memory). |
| A server keeps going down | Crash watch tells you each time (activity log, and your phone if notifications are on). A server set to start again after a crash stops being restarted after three times in ten minutes. |
| Gatekeeper froze | Its watchdog notices: threads waiting on each other for good are seen within a minute, anything else that leaves it not answering after ten minutes, and Gatekeeper restarts itself the way it starts at sign-in. What it saw goes in `host.log`. It is off while a server Gatekeeper runs itself is on (that server's console would be lost). |
| Gatekeeper won't update | It waits while a server it runs itself is on, and updates by itself once that server is off. A failed update puts the old version back; `host.log` has each step ("update: ..."). |
| A Gatekeeper that joined doesn't show in the leader's constellation | Both need a current version. On the leader, look for it as "Asked to join" and allow it. |
| A constellation update never starts | The pips show who is waited for. Amber: that client hasn't seen the update (press **Check for updates** on it, or close it). Red: a Gatekeeper with a server on. It goes with whoever is ready after ten minutes. |
| Everything worked, and stopped months later | Tailscale signed the machine out. Sign in again, and turn off key expiry for it. |
| A settings file looks wrong after a crash or full disk | Gatekeeper writes each file whole before it replaces the old one; the last ten copies of the important ones are in `backups` in its folder. |

## Steam Deck

| What you see | What to do |
| --- | --- |
| Tailscale is gone after a SteamOS update | SteamOS updates can remove it (Tailscale doesn't officially support SteamOS). Install it again in Desktop Mode, following Tailscale's guide for the Deck; Host setup shows when this has happened. |
| The Deck can't be woken from another device | A sleeping Deck can't be woken that way. Keep it plugged in and awake while it serves. |
| The Deck doesn't hand out pack updates | It does only while plugged in, and only while Gatehouse is open (or its Gatekeeper is running). |
| Sleep from a launcher isn't offered | Not supported on SteamOS. |
| Gatekeeper won't start from Gatehouse in Game Mode | Prism Launcher's sandbox has to be allowed to start things outside it; Gatehouse says so where it isn't. |
| Uninstall is greyed in Gatekeeper's window | That window was opened from Gatehouse; open Gatekeeper from the applications menu in Desktop Mode. |

## Logs

| Log | Where |
| --- | --- |
| A pack's update | `pack-sync.log` and `pack-sync-java.log` in the game folder's `minecraft` folder; `pack-relay.log` beside them for an update fetched through Gatehouse |
| Gatekeeper | `host.log` in Gatekeeper's folder (its path is in Options) |
| The October Journey installers | `%TEMP%\GatehouseSetup.log` and `GatehouseUninstall.log` on Windows; the terminal on Linux |

For anything else, open an [issue](https://github.com/scholar-andrew/gatehouse/issues). Leave out player names,
addresses and keys.
