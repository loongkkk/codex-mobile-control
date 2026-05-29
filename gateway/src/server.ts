import { createServer } from "node:http";

import { createGatewayApp } from "./app";
import { loadGatewayRuntimeConfig } from "./gateway-config";
import { attachMobileSocketHub } from "./mobile-socket-hub";
import { createMobileGatewayService } from "./service-impl";

async function main(): Promise<void> {
  const runtimeConfig = loadGatewayRuntimeConfig();
  const port = runtimeConfig.port;
  const host = runtimeConfig.host;
  const authToken = runtimeConfig.authToken;
  const service = await createMobileGatewayService({
    authToken,
    storage: runtimeConfig.storage,
    codex: runtimeConfig.codex,
    desktopBridge: runtimeConfig.desktopBridge
  });
  const app = createGatewayApp({
    service,
    authToken,
    mobileDistDir: runtimeConfig.storage.mobileDistDir,
    uploadRootDir: runtimeConfig.storage.uploadsDir,
    downloadsDir: runtimeConfig.storage.downloadsDir
  });

  const server = createServer(app);
  attachMobileSocketHub({ server, service });
  server.listen(port, host, () => {
    console.log(`Mobile Gateway listening on http://${host}:${port}`);
    console.log(`Mobile Gateway config: ${runtimeConfig.configFilePath}`);
    if (runtimeConfig.created && !process.env.CODEX_MOBILE_TOKEN) {
      console.log(`Mobile Gateway token: ${runtimeConfig.authToken}`);
    }
  });
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
