# Reincarnation

[简体中文](README-zh.md)

**Start a new life. Keep the memories of the last.**

Reincarnation lets you start over in the same Minecraft world. Each rebirth resets your personal progress, begins the
next life, and saves your previous life for you to browse. Your world and builds stay in place.

## Features

- **A fresh start:** reset inventory, Ender Chest, experience, recipes, advancements, and statistics. Your first life is
  Life 1.
- **Past-life records:** browse previous lives in an archive screen, including their dates, rebirth reasons, and player
  information.
- **Optional automatic rebirth:** start a new life after death, with an optional filter for selected causes of death.
- **Optional lifespans:** watch your age increase, receive a warning when life is running out, and choose what happens
  when it ends. Offline time does not count.

## Playing

| Command                      | What it does                                               |
|------------------------------|------------------------------------------------------------|
| `/reincarnation view`        | Open your current life and past-life records.              |
| `/reincarnation history`     | List your past lives in chat.                              |
| `/reincarnation view Alex 1` | View Alex's first life; requires administrator permission. |
| `/reincarnation rebirth @s`  | Start a new life; requires administrator permission.       |
| `/lifespan get @s`           | Check your lifespan; requires administrator permission.    |

Players can browse their own records. Viewing another player's records, starting a new life, and managing lifespans
require administrator permission (level 2).

## Settings

Open **Mods → Reincarnation → Config** to change settings. Server settings require administrator permission.

- **Automatic rebirth and ageing are disabled by default.** Enable either when you want it.
- **Death filtering** can limit automatic rebirth to selected causes of death. The default selection is lifespan
  exhaustion; other deaths use ordinary respawn. Requires automatic rebirth to be enabled.
- **Lifespans** default to 60 years when ageing is enabled. Age starts at zero, and a warning appears when 10% or less
  remains. Starting lifespan, ageing speed, and what happens at the end are configurable.
- **Reset rules** let you choose whether to keep game mode, team membership, and scoreboard scores.

Rebirth resets inventory even with `keepInventory` enabled. Other mods' progression may need compatibility support.
Past-life records are for viewing and cannot be restored to your current player.
