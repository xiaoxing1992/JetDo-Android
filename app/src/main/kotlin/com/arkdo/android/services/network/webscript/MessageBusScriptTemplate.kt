package com.arkdo.android.services.network.webscript

// ==========================================================================
// 与 HarmonyOS 版 services/network/webscript/MessageBusScriptTemplate.ets 逐字对齐。
// MessageBus long poll 必须完整地留在 WebView 文档中运行，原生只接收轻量事件。
// JS 全文原样内嵌。
// ==========================================================================

object MessageBusScriptTemplate {

    /** 注入到 host 文档的长轮询循环脚本。 */
    fun buildMessageBusScript(): String = """
        (() => {
            window.__arkdoMessageBusStopRequested = false;
            window.__arkdoMessageBusChannels = window.__arkdoMessageBusChannels || {};
            window.__arkdoMessageBusAddChannel = (name, position) => {
              const pos = (typeof position === 'number') ? position : -1;
              if (!Object.prototype.hasOwnProperty.call(window.__arkdoMessageBusChannels, name)) {
                window.__arkdoMessageBusChannels[name] = pos;
              }
              const active = window.__arkdoMessageBusActiveState;
              if (active && !Object.prototype.hasOwnProperty.call(active.channels, name)) {
                active.channels[name] = window.__arkdoMessageBusChannels[name];
              }
            };
            window.__arkdoMessageBusRemoveChannel = (name) => {
              delete window.__arkdoMessageBusChannels[name];
              const active = window.__arkdoMessageBusActiveState;
              if (active) {
                delete active.channels[name];
              }
            };
            const emit = (event) => {
              try {
                window.JetDoBridge.onBusEvent(JSON.stringify(event));
              } catch (_) {
              }
            };
            const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
            const uniqueId = () => 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
              const random = (Math.random() * 16) | 0;
              const value = char === 'x' ? random : (random & 0x3) | 0x8;
              return value.toString(16);
            });
            const findLongPollingBaseUrl = (value, depth) => {
              if (value === null || value === undefined || depth > 8) {
                return '';
              }
              if (typeof value === 'string') {
                if (!value.includes('long_polling_base_url')) {
                  return '';
                }
                try {
                  return findLongPollingBaseUrl(JSON.parse(value), depth + 1);
                } catch (_) {
                  return '';
                }
              }
              if (Array.isArray(value)) {
                for (const item of value) {
                  const found = findLongPollingBaseUrl(item, depth + 1);
                  if (found) {
                    return found;
                  }
                }
                return '';
              }
              if (typeof value === 'object') {
                if (typeof value.long_polling_base_url === 'string') {
                  return value.long_polling_base_url;
                }
                for (const key of Object.keys(value)) {
                  const found = findLongPollingBaseUrl(value[key], depth + 1);
                  if (found) {
                    return found;
                  }
                }
              }
              return '';
            };
            const resolvePollBaseUrl = async () => {
              const fallback = new URL('/', window.location.origin).toString();
              const fallbackConfig = {
                baseUrl: fallback,
                sharedSessionKey: '',
                currentUserId: 0,
                source: 'fallback'
              };
              try {
                const response = await fetch(fallback, {
                  credentials: 'include',
                  cache: 'no-store',
                  headers: {
                    'Accept': 'text/html',
                    'X-Requested-With': 'XMLHttpRequest'
                  }
                });
                if (!response.ok) {
                  fallbackConfig.source = 'http-' + response.status;
                  return fallbackConfig;
                }
                const html = await response.text();
                const documentRoot = new DOMParser().parseFromString(html, 'text/html');
                const preload = documentRoot.querySelector('#data-preloaded');
                const rawPreloaded = preload
                  ? (preload.textContent || preload.getAttribute('data-preloaded') || '')
                  : '';
                if (!rawPreloaded) {
                  fallbackConfig.source = 'preload-missing';
                  return fallbackConfig;
                }
                const preloaded = JSON.parse(rawPreloaded);
                let currentUserId = 0;
                try {
                  const currentUser = typeof preloaded.currentUser === 'string'
                    ? JSON.parse(preloaded.currentUser)
                    : preloaded.currentUser;
                  if (currentUser && typeof currentUser.id === 'number') {
                    currentUserId = currentUser.id;
                  }
                } catch (_) {
                }
                const siteSettings = typeof preloaded.siteSettings === 'string'
                  ? JSON.parse(preloaded.siteSettings)
                  : preloaded.siteSettings;
                const configured = siteSettings && typeof siteSettings.long_polling_base_url === 'string'
                  ? siteSettings.long_polling_base_url
                  : findLongPollingBaseUrl(preloaded, 0);
                if (!configured) {
                  fallbackConfig.currentUserId = currentUserId;
                  fallbackConfig.source = 'setting-missing';
                  return fallbackConfig;
                }
                const resolved = new URL(configured, fallback).toString();
                const sharedSessionMeta = documentRoot.querySelector('meta[name=shared_session_key]');
                return {
                  baseUrl: resolved.endsWith('/') ? resolved : resolved + '/',
                  sharedSessionKey: sharedSessionMeta ? (sharedSessionMeta.getAttribute('content') || '') : '',
                  currentUserId: currentUserId,
                  source: 'site-settings'
                };
              } catch (_) {
                fallbackConfig.source = 'parse-error';
                return fallbackConfig;
              }
            };
            const start = async () => {
              if (window.__arkdoMessageBusStopRequested) {
                return;
              }
              if (window.__arkdoMessageBusStarted) {
                return;
              }
              window.__arkdoMessageBusStarted = true;
              const generation = (window.__arkdoMessageBusGeneration || 0) + 1;
              window.__arkdoMessageBusGeneration = generation;
              const state = {
                clientId: uniqueId(),
                baseUrl: '',
                sharedSessionKey: '',
                channels: { '/latest': -1, '/new': -1 },
                seq: 0,
                running: true,
                retryDelayMs: 1000
              };
              emit({ type: 'status', stage: 'started', message: 'message bus loop started' });
              const pollConfig = await resolvePollBaseUrl();
              state.baseUrl = pollConfig.baseUrl;
              state.sharedSessionKey = pollConfig.sharedSessionKey;
              if (pollConfig.currentUserId > 0) {
                state.channels['/notification/' + pollConfig.currentUserId] = -1;
              }
              const extraChannels = window.__arkdoMessageBusChannels || {};
              Object.keys(extraChannels).forEach((name) => {
                state.channels[name] = extraChannels[name];
              });
              window.__arkdoMessageBusActiveState = state;
              emit({
                type: 'status',
                stage: 'config-ready',
                message: state.baseUrl + ' source=' + pollConfig.source +
                  ' sharedSessionKey=' + String(Boolean(state.sharedSessionKey)) +
                  ' userId=' + String(pollConfig.currentUserId)
              });
              const backoff = async () => {
                const waitMs = state.retryDelayMs;
                state.retryDelayMs = Math.min(state.retryDelayMs * 2, 30000);
                await delay(waitMs);
              };
              const stopForChallenge = (event) => {
                emit(event);
                state.running = false;
                window.__arkdoMessageBusStarted = false;
                setTimeout(start, 30000);
              };

              const poll = async () => {
                while (state.running && !window.__arkdoMessageBusStopRequested &&
                  generation === window.__arkdoMessageBusGeneration) {
                  const body = new URLSearchParams();
                  Object.keys(state.channels).forEach((channel) => {
                    body.append(channel, String(state.channels[channel]));
                  });
                  body.append('__seq', String(++state.seq));

                  try {
                    const headers = {
                      'Content-Type': 'application/x-www-form-urlencoded',
                      'X-Requested-With': 'XMLHttpRequest',
                      'X-SILENCE-LOGGER': 'true',
                      'Dont-Chunk': 'true'
                    };
                    if (state.sharedSessionKey && new URL(state.baseUrl).origin !== window.location.origin) {
                      headers['X-Shared-Session-Key'] = state.sharedSessionKey;
                    }
                    const response = await fetch(
                      new URL('message-bus/' + encodeURIComponent(state.clientId) + '/poll', state.baseUrl).toString(),
                      {
                        method: 'POST',
                        credentials: 'include',
                        cache: 'no-store',
                        headers: headers,
                        body: body.toString()
                      }
                    );
                    const responseText = await response.text();
                    if (response.status === 403 || response.status === 429) {
                      stopForChallenge({ type: 'challenge', statusCode: response.status, message: 'message bus blocked' });
                      return;
                    }

                    let messages;
                    try {
                      messages = JSON.parse(responseText);
                    } catch (_) {
                      const contentType = response.headers ? (response.headers.get('content-type') || '') : '';
                      const lowerBody = responseText.toLowerCase();
                      const looksLikeChallenge = response.status === 403 || response.status === 429 ||
                        contentType.includes('text/html') || lowerBody.includes('cf-chl') ||
                        lowerBody.includes('just a moment');
                      if (looksLikeChallenge) {
                        stopForChallenge({
                          type: 'challenge',
                          statusCode: response.status,
                          message: 'message bus returned challenge html'
                        });
                        return;
                      }
                      emit({
                        type: 'error',
                        stage: 'poll-response',
                        statusCode: response.status,
                        message: 'message bus returned non-json'
                      });
                      await backoff();
                      continue;
                    }
                    if (!response.ok || !Array.isArray(messages)) {
                      emit({ type: 'error', statusCode: response.status, message: 'message bus poll failed' });
                      await backoff();
                      continue;
                    }

                    state.retryDelayMs = 1000;
                    for (const message of messages) {
                      if (!message || typeof message.channel !== 'string') {
                        continue;
                      }
                      if (message.channel === '/__status' && message.data && typeof message.data === 'object') {
                        Object.keys(state.channels).forEach((channel) => {
                          if (typeof message.data[channel] === 'number') {
                            state.channels[channel] = message.data[channel];
                            emit({
                              type: 'status',
                              stage: 'cursor-ready',
                              channel: channel,
                              messageId: message.data[channel],
                              message: 'message bus cursor initialized'
                            });
                          }
                        });
                        continue;
                      }
                      if (typeof message.message_id !== 'number') {
                        continue;
                      }
                      if (Object.prototype.hasOwnProperty.call(state.channels, message.channel)) {
                        state.channels[message.channel] = message.message_id;
                        if (window.__arkdoMessageBusChannels &&
                          Object.prototype.hasOwnProperty.call(window.__arkdoMessageBusChannels, message.channel)) {
                          window.__arkdoMessageBusChannels[message.channel] = message.message_id;
                        }
                      }
                      emit({
                        type: 'message',
                        channel: message.channel,
                        messageId: message.message_id,
                        data: message.data
                      });
                    }
                  } catch (error) {
                    emit({ type: 'error', statusCode: 0, message: String(error) });
                    await backoff();
                  }
                }
                if (generation === window.__arkdoMessageBusGeneration) {
                  window.__arkdoMessageBusStarted = false;
                  window.__arkdoMessageBusActiveState = undefined;
                }
              };

              poll();
            };

            start();
            return 'started';
          })();
    """.trimIndent()
}
