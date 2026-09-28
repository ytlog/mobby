const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const host = '127.0.0.1';
const port = 18790;
const page = path.join(__dirname, 'index.html');

http.createServer((request, response) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405, { Allow: 'GET, HEAD' });
    response.end();
    return;
  }
  if (request.url !== '/' && request.url !== '/index.html') {
    response.writeHead(404);
    response.end('Not found');
    return;
  }

  fs.readFile(page, (error, content) => {
    if (error) {
      response.writeHead(500);
      response.end('Unable to load page');
      return;
    }
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': content.length,
      'Cache-Control': 'no-cache'
    });
    response.end(request.method === 'HEAD' ? undefined : content);
  });
}).listen(port, host, () => {
  console.log(`旅行打包清单：http://${host}:${port}/`);
});
