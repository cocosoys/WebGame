#!/bin/bash
# Launch Forge 1.12.2 client inside KasmVNC display :99
export DISPLAY=:99
export LWJGL_DISABLE_XRANDR=true
MC=/home/webgame/mc/.minecraft
VER=1.12.2-Forge_14.23.5.2864

CP="$MC/versions/$VER/$VER.jar"
while IFS= read -r -d '' j; do
  CP="$CP:$j"
done < <(find "$MC/libraries" -name '*.jar' -print0)

exec java \
  -Xmx2G -Xms512M \
  -Djava.library.path="$MC/natives-linux" \
  -Dfml.ignoreInvalidMinecraftCertificates=true \
  -Dfml.ignorePatchDiscrepancies=true \
  -cp "$CP" \
  net.minecraft.launchwrapper.Launch \
  --username "TestUser" \
  --version "$VER" \
  --gameDir "$MC" \
  --assetsDir "$MC/assets" \
  --assetIndex "1.12" \
  --uuid "00000000-0000-0000-0000-000000000000" \
  --accessToken "0" \
  --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker \
  --versionType "Forge" \
  --server "127.0.0.1" --port 25574 \
  "$@"
