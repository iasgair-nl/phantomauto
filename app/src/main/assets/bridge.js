// Injected into the real PhantomChat PWA (unmodified) after each page load.
// Attaches to window.__phantomchatChatAPI - a global the app already exposes
// unconditionally from its ChatAPI constructor (src/lib/phantomchat/chat-api.ts)
// for exactly this kind of late-attaching integration (the app's own
// phantomchat-bridge.ts uses the same poll-and-retry idiom internally).
//
// Exposes three things to the native side:
//   - wraps chatApi.onMessage to also forward incoming DMs to Android
//     via window.AndroidBridge.postMessage(json)
//   - window.sendReply(conversationId, text) to send a reply
//   - window.getConversationsSnapshot() for cold-start notification catch-up
(function () {
  if (window.__phantomAutoBridgeInstalled) {
    return;
  }
  window.__phantomAutoBridgeInstalled = true;

  var ATTACH_RETRY_MS = 300;

  function post(type, payload) {
    if (window.AndroidBridge && window.AndroidBridge.postMessage) {
      window.AndroidBridge.postMessage(JSON.stringify({type: type, payload: payload}));
    }
  }

  function shortPubkey(pubkey) {
    return 'npub...' + String(pubkey || '').slice(0, 12);
  }

  // Read-only lookup against PhantomChat's own contact-name cache
  // (src/lib/phantomchat/virtual-peers-db.ts: DB phantomchat-virtual-peers,
  // store 'mappings', keyPath 'pubkey', field 'displayName'). Never written
  // to from here - only PhantomChat's own code populates it.
  function resolveDisplayName(pubkey) {
    return new Promise(function (resolve) {
      try {
        var req = indexedDB.open('phantomchat-virtual-peers');
        req.onerror = function () { resolve(shortPubkey(pubkey)); };
        req.onsuccess = function () {
          var db = req.result;
          if (!db.objectStoreNames.contains('mappings')) {
            resolve(shortPubkey(pubkey));
            return;
          }
          var getReq = db.transaction('mappings', 'readonly')
            .objectStore('mappings')
            .get(pubkey);
          getReq.onsuccess = function () {
            var record = getReq.result;
            resolve((record && record.displayName) || shortPubkey(pubkey));
          };
          getReq.onerror = function () { resolve(shortPubkey(pubkey)); };
        };
      } catch (e) {
        resolve(shortPubkey(pubkey));
      }
    });
  }

  // Same list of known peer pubkeys the contact-name cache holds, used to
  // enumerate conversations for the cold-start snapshot (see
  // getConversationsSnapshot below).
  function listKnownPeerPubkeys() {
    return new Promise(function (resolve) {
      try {
        var req = indexedDB.open('phantomchat-virtual-peers');
        req.onerror = function () { resolve([]); };
        req.onsuccess = function () {
          var db = req.result;
          if (!db.objectStoreNames.contains('mappings')) {
            resolve([]);
            return;
          }
          var pubkeys = [];
          var cursorReq = db.transaction('mappings', 'readonly')
            .objectStore('mappings')
            .openCursor();
          cursorReq.onsuccess = function (event) {
            var cursor = event.target.result;
            if (cursor) {
              pubkeys.push(cursor.key);
              cursor.continue();
            } else {
              resolve(pubkeys);
            }
          };
          cursorReq.onerror = function () { resolve([]); };
        };
      } catch (e) {
        resolve([]);
      }
    });
  }

  function conversationIdFor(pubkeyA, pubkeyB) {
    // Mirrors MessageStore.getConversationId (src/lib/phantomchat/message-store.ts):
    // sort both hex pubkeys and join with ':'. Deliberately reimplemented inline
    // (one line, stable, documented format) rather than reaching into the
    // module's internals to build it.
    return [pubkeyA, pubkeyB].sort().join(':');
  }

  function toBridgeMessage(chatApi, msg) {
    var ownId = chatApi.getOwnId();
    var peerPubkey = msg.from === ownId ? msg.to : msg.from;
    var conversationId = conversationIdFor(msg.from, msg.to);
    return resolveDisplayName(peerPubkey).then(function (senderName) {
      return {
        conversationId: conversationId,
        peerPubkey: peerPubkey,
        senderPubkey: msg.from,
        senderName: senderName,
        text: msg.content || (msg.fileMetadata ? '[bestand]' : ''),
        timestamp: msg.timestamp
      };
    });
  }

  function installBridge(chatApi) {
    // The app's own UI reassigns chatApi.onMessage directly whenever a chat view
    // opens/re-renders (observed: opening a conversation replaces it with the app's
    // internal render handler, silently dropping a plain one-time wrap). A property
    // trap survives that: the getter always hands back our dispatcher, so internal
    // code calling this.onMessage(msg) always reaches us; the setter just captures
    // whatever the app assigns so we can still forward to it.
    var appHandler = chatApi.onMessage || null;

    function dispatch(msg) {
      if (typeof appHandler === 'function') {
        appHandler(msg);
      }
      if (!msg || !msg.from || !msg.to || msg.from === chatApi.getOwnId()) {
        return; // no peer info, or our own self-echo of a sent message
      }
      toBridgeMessage(chatApi, msg).then(function (payload) {
        post('message', payload);
      });
    }

    Object.defineProperty(chatApi, 'onMessage', {
      configurable: true,
      enumerable: true,
      get: function () { return dispatch; },
      set: function (fn) { appHandler = fn; }
    });

    window.sendReply = function (conversationId, text) {
      var ownId = chatApi.getOwnId();
      var parts = String(conversationId).split(':');
      var peerPubkey = parts[0] === ownId ? parts[1] : parts[0];
      chatApi.setActivePeer(peerPubkey);
      return chatApi.sendText(text);
    };

    // Best-effort catch-up for the notification service's cold start: latest
    // message per known peer. There is no public "list all conversations with
    // unread count" API on ChatAPI, so this intentionally omits unread counts
    // rather than reaching into private internals to approximate them.
    window.getConversationsSnapshot = function () {
      return listKnownPeerPubkeys().then(function (peerPubkeys) {
        return Promise.all(
          peerPubkeys.map(function (peerPubkey) {
            return Promise.all([
              chatApi.loadHistory(peerPubkey, 1),
              resolveDisplayName(peerPubkey)
            ]).then(function (results) {
              var history = results[0];
              var senderName = results[1];
              var latest = history && history[0];
              if (!latest) {
                return null;
              }
              return {
                conversationId: conversationIdFor(chatApi.getOwnId(), peerPubkey),
                peerPubkey: peerPubkey,
                senderName: senderName,
                text: latest.content || (latest.fileMetadata ? '[bestand]' : ''),
                timestamp: latest.timestamp
              };
            });
          })
        ).then(function (conversations) {
          return conversations.filter(Boolean);
        });
      });
    };

    post('ready', {ownId: chatApi.getOwnId()});
  }

  function attach() {
    // This is a single-page app loaded exactly once for the WebView's entire
    // lifetime (see PhantomAutoService), so onPageFinished - and this poll -
    // only ever runs once per process. Identity creation/import or a PIN
    // unlock can easily take longer than any short timeout (the user is
    // typing/reading), so this deliberately polls indefinitely rather than
    // giving up after N attempts - there is no second page load to retry on.
    var timer = setInterval(function () {
      var chatApi = window.__phantomchatChatAPI;
      if (chatApi) {
        clearInterval(timer);
        installBridge(chatApi);
      }
    }, ATTACH_RETRY_MS);
  }

  attach();
})();
