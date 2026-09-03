import http from 'node:http';

const upstream = 'https://rentaxis.uaenorth.cloudapp.azure.com';
const port = Number(process.env.CAPTURE_PROXY_PORT || 18080);

const server = http.createServer(async (req, res) => {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const headers = { ...req.headers };
    delete headers.host;
    delete headers.connection;
    headers['accept-encoding'] = 'identity';
    const response = await fetch(`${upstream}${req.url}`, {
      method: req.method,
      headers,
      body: chunks.length ? Buffer.concat(chunks) : undefined,
      redirect: 'manual',
    });
    const responseHeaders = Object.fromEntries(response.headers.entries());
    delete responseHeaders['content-encoding'];
    delete responseHeaders['content-length'];
    delete responseHeaders['transfer-encoding'];
    res.writeHead(response.status, responseHeaders);
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    res.writeHead(502, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'capture proxy upstream failure' }));
    console.error(error);
  }
});

server.listen(port, '127.0.0.1', () => console.log(`capture proxy listening on 127.0.0.1:${port}`));
