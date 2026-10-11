# Windows test fixtures

Update files for checking `-Xjr:update-check` channel rules on Windows, read from GitHub (raw.githubusercontent.com) because jr on Windows trusts only real https certificates. `channels-beta`: a beta is out, stable is older. `channels-final`: the final release is out after its beta. Stamp a config with `update.url` pointing at one of them, `app.version` and `update.channel` into a copy of jr.exe (`jr.exe -Xjr:edit=t.exe -Xjr:resource.RCDATA.JRC=t.jrc.json`) and run `t.exe -Xjr:update-check`.
