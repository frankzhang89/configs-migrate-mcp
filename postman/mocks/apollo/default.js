// Contract: apolloconfig/apollo docs/zh/client/other-language-client-user-guide.md.
// Synthetic data only. This peer implements published configuration reads, not Portal APIs.
const http = require('http');
const yaml = "# agent-converted\r\napp:\r\n  name: '订单'\r\n  enabled: 'true'\r\n";
http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const reply = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json;charset=UTF-8' });
    res.end(JSON.stringify(body));
  };
  // @endpoint GET /services/config
  if (req.method === 'GET' && url.pathname === '/services/config') {
    // Path routing strips /apollo before forwarding. Advertise the public simulation URL.
    return reply(200, [{ homepageUrl: process.env.APOLLO_SIM_PUBLIC_URL || 'http://127.0.0.1:14900/apollo' }]);
  }
  // @endpoint GET /configs/{appId}/{cluster}/{namespace}
  if (req.method === 'GET' && /^\/configs\/[^/]+\/[^/]+\/[^/]+$/.test(url.pathname)) {
    const namespace = decodeURIComponent(url.pathname.split('/').pop());
    if (namespace === 'missing') return reply(404, { message: 'namespace has no release' });
    if (namespace === 'denied') return reply(401, { message: 'invalid signature' });
    const configurations = /\.ya?ml$/.test(namespace)
      ? { content: yaml } : { 'app.name': '订单', 'app.enabled': 'true', 'app.code': '00123' };
    return reply(200, { appId: 'source-app', cluster: 'default', namespaceName: namespace, configurations, releaseKey: 'release-1' });
  }
  return reply(501, { message: 'Endpoint not modeled' });
}).listen(process.env.PORT || 14901);
