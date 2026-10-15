# Compile-only runtime API mirrors

`zauctionhouse-api-4.0.1.3.jar` contains only the public `api` package extracted
from the exact active zAuctionHouse runtime JAR. It is used only on the compile
classpath and is excluded from the ARC shadow JAR.

- Source: `https://github.com/GroupeZ-dev/zAuctionHouse`
- Version: `4.0.1.3`
- Active runtime SHA-256: `7599d78eddfbc3969e8f67c4dfe6dd27c34fb98dea0ef590bb0503d3b075c488`
- API mirror SHA-256: `564c13a1e1064dea4fec43f1cb8f1f51727446739430c23ad15c7d248b8aff06`
- License: MIT; see `LICENSE-zAuctionHouse.txt`

`gravesx-api-2026.4.9.2.jar` is the official compile-only API artifact from the exact
GravesX release deployed on spawn and survival. It is never shaded into ARC.

- Source: `https://github.com/Legoman99573/GravesX`
- Source tag/commit: `2026.4.9.2` / `29a295eca8d13efdd82b40aa0de1a196a10f9b52`
- API artifact: `https://github.com/Legoman99573/GravesX/releases/download/2026.4.9.2/GravesX-2026.4.9.2-api.jar`
- API artifact SHA-256: `c5e1027c7117d3e1c4c486d81508ef5285fc4bb68036b99294286597ce97ba5e`
- Active full-plugin SHA-256 on both servers: `62253de35ee5f61dc70acd8f851d2fafb8e42954c3f3f4b1aae390a94bbbdb11`
- License: GPL-3.0; see `LICENSE-GravesX.txt`
