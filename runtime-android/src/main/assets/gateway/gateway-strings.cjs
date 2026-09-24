'use strict';

// Node runs outside the JVM. Keep its user-facing diagnostics in this catalogue.
class GatewayStrings {
  constructor(language = process.env.MOBBY_LANGUAGE) { this.english = language === 'en'; }
  text(chinese, english) { return this.english ? english : chinese; }
  get invalidProtocol() { return this.text('网关协议无效', 'Invalid gateway protocol'); }
  get invalidUrl() { return this.text('网关 URL 无效', 'Invalid gateway URL'); }
  get bodyTooLarge() { return this.text('网关请求或响应超过 16 MiB', 'Gateway request or response exceeds 16 MiB'); }
  get unsupportedPath() { return this.text('不支持的本地网关路径', 'Unsupported local gateway path'); }
  get protocolMismatch() { return this.text('网关协议不匹配，暂不提供转换', 'Gateway protocol mismatch; conversion is not supported'); }
  get unsupportedEncoding() { return this.text('不支持的请求压缩格式', 'Unsupported request encoding'); }
  get invalidRequest() { return this.text('不支持的请求格式', 'Unsupported request format'); }
  httpFailure(status) { return this.text(`网关 HTTP ${status}，请检查地址、协议、模型和密钥`, `Gateway HTTP ${status}. Check address, protocol, model and key`); }
  get requestFailed() { return this.text('网关请求失败，请检查网络与协议配置', 'Gateway request failed. Check network and protocol configuration'); }
  get nativeProtocolRequired() { return this.text('网关协议必须与 Agent 原生协议一致；暂不提供转换', 'Gateway protocol must match the Agent’s native protocol; conversion is not supported'); }
  get bridgeRequired() { return this.text('必须使用本地桥接', 'A local bridge is required'); }
  get unsupportedAgent() { return this.text('不支持的 Agent', 'Unsupported Agent'); }
  get invalidInputFile() { return this.text('不支持的 Agent 输入文件', 'Unsupported Agent input file'); }
  get agentStartFailed() { return this.text('无法启动 Agent', 'Could not start Agent'); }
  get bridgeStartFailed() { return this.text('无法启动本地桥接，请检查 Agent 协议与网关配置', 'Could not start local bridge. Check Agent protocol and gateway configuration'); }
}

// Only these deliberately generated errors may be returned to clients.
class GatewayError extends Error {}
module.exports = {GatewayStrings, GatewayError};
