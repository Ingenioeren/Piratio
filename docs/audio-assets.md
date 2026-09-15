# Pirat.io audio assets

Expected Android `res/raw` filenames:

- `piratio_intro.ogg` — main menu music
- `piratio_sea_shanty_1.ogg` … `piratio_sea_shanty_5.ogg` — gameplay playlist
- `cannon_shot.ogg` — local player broadside SFX

`AudioController` rotates the five shanties without immediately repeating a track. `AudioGameView` only triggers cannon SFX from the local player's firing state, so bot/remotes do not create cannon spam on the device.

The supplied music masters are 48 kHz OGG. For prototype APK size, optimized copies may be used; retain the supplied masters for final release encoding.
