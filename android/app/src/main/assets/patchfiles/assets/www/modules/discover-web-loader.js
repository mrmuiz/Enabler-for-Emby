(function () {
    'use strict';
    if (window.__discoverWebLoader) { return; }
    window.__discoverWebLoader = 1;
    // Patched app: the Discover tile always opens the Discover page (never the channel tiles)
    window.__discoverAlwaysTile = true;

    // ---- Emby app capabilities: plugin pages (always) and the server menu (optional) ----
    // This only changes client capabilities; server permissions still apply.
    // The patcher sets window.__discoverAdminMenu = false when the server menu must stay off;
    // plugin pages (Discover in the menu) are always allowed.
    var adminMenu = window.__discoverAdminMenu !== false;
    function patchAppHost(host) {
        if (!host || typeof host.supports !== 'function' || host.__dcAdminMenu) { return false; }
        var originalSupports = host.supports;
        host.supports = function (feature) {
            var name = String(feature || '').toLowerCase();
            if (name === 'serversetup' && adminMenu) { return true; }
            if (name === 'restrictedplugins') { return false; }
            return originalSupports.apply(this, arguments);
        };
        host.__dcAdminMenu = true;
        if (host.appHost && host.appHost !== host) { patchAppHost(host.appHost); }
        log('plugin pages allowed; server management menu ' + (adminMenu ? 'enabled' : 'disabled in the patcher'));
        return true;
    }

    function enableAppFeatures(attempt) {
        attempt = attempt || 0;
        if (attempt >= 120) { log('appHost not found; capability change not applied'); return; }
        try {
            if (window.Emby && typeof window.Emby.importModule === 'function') {
                Promise.resolve(window.Emby.importModule('./modules/common/servicelocator.js')).then(function (module) {
                    var locator = (module && (module.default || module)) || {};
                    if (!patchAppHost(locator.appHost || (module && module.appHost))) {
                        setTimeout(function () { enableAppFeatures(attempt + 1); }, 500);
                    }
                }, function () {
                    setTimeout(function () { enableAppFeatures(attempt + 1); }, 500);
                });
                return;
            }
        } catch (e) { }
        setTimeout(function () { enableAppFeatures(attempt + 1); }, 500);
    }
    enableAppFeatures();

    var CHANNEL_NAME = 'Discover Media';
    var KNOWN_UI = ['discoverui1150', 'discoverui1149', 'discoverui1148', 'discoverui1147', 'discoverui1146', 'discoverui1145', 'discoverui1144', 'discoverui1143', 'discoverui1142', 'discoverui1141', 'discoverui1140', 'discoverui1139', 'discoverui1138', 'discoverui1137', 'discoverui1136', 'discoverui1135', 'discoverui1134', 'discoverui1133', 'discoverui1132', 'discoverui1131', 'discoverui1130', 'discoverui1129', 'discoverui1128', 'discoverui1127', 'discoverui1126', 'discoverui1125', 'discoverui1124', 'discoverui1123', 'discoverui1122', 'discoverui1121', 'discoverui1120', 'discoverui1119', 'discoverui1118', 'discoverui1117', 'discoverui1116', 'discoverui1115', 'discoverui1114', 'discoverui1113', 'discoverui1109', 'discoverui1108', 'discoverui1107', 'discoverui1106', 'discoverui1105', 'discoverui1104', 'discoverui1103', 'discoverui1102', 'discoverui1101', 'discoverui1100', 'discoverui1099', 'discoverui1098', 'discoverui1097', 'discoverui1096', 'discoverui1095', 'discoverui1094', 'discoverui1093', 'discoverui1092', 'discoverui1091', 'discoverui1090', 'discoverui1089', 'discoverui1088', 'discoverui1087', 'discoverui1086', 'discoverui1085', 'discoverui1084', 'discoverui1083', 'discoverui1082', 'discoverui1081', 'discoverui1080', 'discoverui1079', 'discoverui1078', 'discoverui1077', 'discoverui1076', 'discoverui1075'];
    var KNOWN_BTN = ['discoverbuttonjs1433', 'discoverbuttonjs1432', 'discoverbuttonjs1431', 'discoverbuttonjs1430', 'discoverbuttonjs1429', 'discoverbuttonjs1428', 'discoverbuttonjs1427', 'discoverbuttonjs1426', 'discoverbuttonjs1425', 'discoverbuttonjs1424', 'discoverbuttonjs1423', 'discoverbuttonjs1422', 'discoverbuttonjs1421', 'discoverbuttonjs1420', 'discoverbuttonjs1419', 'discoverbuttonjs1418', 'discoverbuttonjs1417', 'discoverbuttonjs1416', 'discoverbuttonjs1415', 'discoverbuttonjs1414', 'discoverbuttonjs1413', 'discoverbuttonjs1412', 'discoverbuttonjs1411', 'discoverbuttonjs1410', 'discoverbuttonjs1409', 'discoverbuttonjs1408', 'discoverbuttonjs1407', 'discoverbuttonjs1406', 'discoverbuttonjs1405', 'discoverbuttonjs1404', 'discoverbuttonjs1403', 'discoverbuttonjs1402', 'discoverbuttonjs1401', 'discoverbuttonjs1400', 'discoverbuttonjs1399', 'discoverbuttonjs1398', 'discoverbuttonjs1397', 'discoverbuttonjs1396', 'discoverbuttonjs1395', 'discoverbuttonjs1394', 'discoverbuttonjs1393', 'discoverbuttonjs1392', 'discoverbuttonjs1391', 'discoverbuttonjs1390', 'discoverbuttonjs1389', 'discoverbuttonjs1388', 'discoverbuttonjs1387', 'discoverbuttonjs1386', 'discoverbuttonjs1385', 'discoverbuttonjs1384', 'discoverbuttonjs1383', 'discoverbuttonjs1382', 'discoverbuttonjs1381', 'discoverbuttonjs1380', 'discoverbuttonjs1379', 'discoverbuttonjs1378', 'discoverbuttonjs1377', 'discoverbuttonjs1376', 'discoverbuttonjs1375', 'discoverbuttonjs1374', 'discoverbuttonjs1373', 'discoverbuttonjs1372', 'discoverbuttonjs1371', 'discoverbuttonjs1370', 'discoverbuttonjs1360', 'discoverbuttonjs1354', 'discoverbuttonjs1353', 'discoverbuttonjs1352', 'discoverbuttonjs1351', 'discoverbuttonjs1350', 'discoverbuttonjs1340'];

    function log(m) { try { console.log('[Discover web] ' + m); } catch (e) { } }
    function api() {
        var a = window.ApiClient;
        if (!a || typeof a.getUrl !== 'function') { return null; }
        if (typeof a.accessToken === 'function' && !a.accessToken()) { return null; }
        return a;
    }

    var info = null, infoLoading = null;
    function serverKey(a) { try { return (a.serverId && a.serverId()) + '|' + (a.getCurrentUserId && a.getCurrentUserId()); } catch (e) { return ''; } }
    function items(r) { return (r && (r.Items || (r.length ? r : null))) || []; }
    function newest(names, re) {
        return names.filter(function (n) { return re.test(n); })
            .sort(function (x, y) { return parseInt(y.replace(/\D/g, ''), 10) - parseInt(x.replace(/\D/g, ''), 10); });
    }

    function loadInfo() {
        var a = api();
        if (!a) { return Promise.resolve(null); }
        var key = serverKey(a);
        if (info && info.key === key) { return Promise.resolve(info); }
        if (infoLoading && infoLoading.key === key) { return infoLoading.p; }
        var uid = a.getCurrentUserId();
        var safe = function (p) { return Promise.resolve(p).catch(function () { return null; }); };
        var p = Promise.all([
            safe(a.getJSON(a.getUrl('web/configurationpages', { PageType: 'PluginConfiguration', UserId: uid }))),
            safe(a.getJSON(a.getUrl('Channels', { UserId: uid }))),
            safe(a.getJSON(a.getUrl('Users/' + uid + '/Views'))),
            safe(a.getJSON(a.getUrl('Discover/ChannelInfo'))),
            // The server reports the current page names itself (the button script is not in the list above);
            // so even an old copy of this script baked into a custom app always loads the newest version.
            safe(a.getJSON(a.getUrl('Discover/LoaderConfig', { _: Date.now() })))
        ]).then(function (r) {
            var names = [];
            items(r[0]).forEach(function (pg) { var n = pg && (pg.Name || pg.name); if (n && names.indexOf(n) === -1) { names.push(n); } });
            var ids = [];
            items(r[1]).concat(items(r[2])).forEach(function (it) {
                if (it && it.Name === CHANNEL_NAME && it.Id) {
                    var id = String(it.Id).toLowerCase();
                    if (ids.indexOf(id) === -1) { ids.push(id); }
                }
            });
            // Renamed channel (e.g. "Requests"): the server knows which channel belongs to Discover.
            if (r[3] && r[3].Id) { var cid = String(r[3].Id).toLowerCase(); if (ids.indexOf(cid) === -1) { ids.push(cid); } }
            var ui = newest(names, /^discoverui\d+$/i);
            var btn = newest(names, /^discoverbuttonjs\d+$/i);
            if (r[4] && r[4].UiPage) { ui = [r[4].UiPage].concat(ui.filter(function (n) { return n !== r[4].UiPage; })); }
            if (r[4] && r[4].ButtonPage) { btn = [r[4].ButtonPage].concat(btn.filter(function (n) { return n !== r[4].ButtonPage; })); }
            KNOWN_UI.forEach(function (n) { if (ui.indexOf(n) === -1) { ui.push(n); } });
            KNOWN_BTN.forEach(function (n) { if (btn.indexOf(n) === -1) { btn.push(n); } });
            info = { key: key, ids: ids, uiPage: ui[0], buttonPages: btn };
            log('channel ids ' + (ids.join(',') || 'not found') + ', page ' + info.uiPage);
            return info;
        }, function () { infoLoading = null; return null; });
        infoLoading = { key: key, p: p };
        return p;
    }

    // ---- Load the request button globally ----
    var buttonTried = false;
    function buttonActive() { return !!window.__discoverRequestButtonVersion; }
    function loadButton(a, names, i) {
        if (buttonActive() || !names) { return; }
        if (i >= names.length) { return; }
        var s = document.createElement('script');
        s.src = a.getUrl('web/ConfigurationPage', { name: names[i] }) + '&_=' + Date.now();
        s.async = true;
        s.onload = function () { if (buttonActive()) { log('button active: ' + names[i]); } else { loadButton(a, names, i + 1); } };
        s.onerror = function () { if (s.parentNode) { s.parentNode.removeChild(s); } loadButton(a, names, i + 1); };
        document.head.appendChild(s);
    }

    // ---- tile/channel -> Discover ----
    function routeOf(url) { if (!url) { return ''; } var i = url.indexOf('#!'); return i !== -1 ? url.substr(i + 2).toLowerCase() : ''; }
    function targetsChannel(route) {
        if (!info || !info.ids.length || !route) { return false; }
        for (var k = 0; k < info.ids.length; k++) {
            var id = info.ids[k].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            if (/^\/(list|videos)\b/.test(route) && new RegExp('[?&]parentid=' + id + '(&|$)').test(route)) { return true; }
            if (/^\/(item|channel)\b/.test(route) && new RegExp('[?&]id=' + id + '(&|$)').test(route)) { return true; }
        }
        return false;
    }
    // window.__discoverStay: set by the Discover page's channel button so the channel root opens normally
    // (also when coming back from a folder). Cleared as soon as the user leaves channel routes.
    function inChannelRoutes(route) { return /^\/(list|videos|item|channel)\b/.test(route); }
    function stayActive() {
        if (window.__discoverStay && !inChannelRoutes(routeOf(location.href))) { window.__discoverStay = false; }
        return !!window.__discoverStay;
    }

    // Setting "Tile opens the Discover page", read from the server (never cached): null = not known yet.
    var redirectOn = null, redirectAt = 0, redirectLoading = null;
    function refreshConfig(force) {
        // A custom app (e.g. the LG app) can declare that the tile always opens Discover there, regardless of the
        // setting (which is meant for the browser): window.__discoverAlwaysTile = true before this script.
        if (window.__discoverAlwaysTile === true) {
            if (redirectOn !== true) { log('app: tile always opens Discover'); }
            redirectOn = true; redirectAt = Date.now();
            return Promise.resolve(true);
        }
        var a = api();
        if (!a) { return Promise.resolve(redirectOn); }
        if (redirectLoading) { return redirectLoading; }
        if (!force && Date.now() - redirectAt < 3000) { return Promise.resolve(redirectOn); }
        redirectLoading = Promise.resolve(a.getJSON(a.getUrl('Discover/LoaderConfig', { _: Date.now() }))).then(function (r) {
            var v = !!(r && r.RedirectTile);
            if (v !== redirectOn) { log('config RedirectTile = ' + v + ' (was ' + redirectOn + ')'); }
            redirectOn = v; redirectAt = Date.now(); redirectLoading = null;
            return v;
        }, function (err) {
            log('loading config failed (' + ((err && (err.status || err.message)) || err) + '): no redirect');
            redirectLoading = null; redirectOn = false; redirectAt = Date.now();
            return false;
        });
        return redirectLoading;
    }
    window.__discoverDebug = function () { return { backdrop: { allowed: (typeof bdAllowed === 'function') ? bdAllowed() : null, on: bd && bd.on, urls: bd && bd.urls ? bd.urls.length : 0 }, redirectOn: redirectOn, configAgeMs: Date.now() - redirectAt, stay: !!window.__discoverStay, route: routeOf(location.href), channelIds: info && info.ids }; };

    // Decision: redirect only for a fresh open of the channel root (tile / menu), never when the setting is off,
    // when the user asked for the channel tiles, or when coming back (Back) from inside the channel.
    var prevRoute = '', initialChecked = false;
    function shouldRedirect(route, navType, fromRoute, ignoreCfg) {
        var why = null;
        if (!ignoreCfg && redirectOn !== true) { why = 'setting off'; }
        else if (!targetsChannel(route)) { why = 'not a channel route'; }
        else if (window.__discoverAlwaysTile === true) { why = null; }   // custom app: always Discover, never the tiles
        else if (window.__discoverStay) { why = 'channel tiles button active'; }
        else if (navType === 'traverse' && inChannelRoutes(fromRoute || '')) { why = 'back from inside the channel'; }
        if (why && targetsChannel(route)) { log('decision: do NOT redirect (' + why + '), config=' + redirectOn); }
        else if (!why) { log('decision: redirect, config=' + redirectOn); }
        return !why;
    }
    function discoverHash() { return '#!/configurationpage?name=' + encodeURIComponent((info && info.uiPage) || KNOWN_UI[0]); }
    function redirect() {
        var url = location.href.split('#')[0] + discoverHash();
        if (window.navigation && typeof navigation.navigate === 'function') { navigation.navigate(url, { history: 'replace' }); }
        else { location.replace(url); }
    }

    if (window.navigation && typeof navigation.addEventListener === 'function') {
        navigation.addEventListener('navigate', function (e) {
            try {
                var route = routeOf(e.destination && e.destination.url);
                var from = prevRoute;
                prevRoute = route;
                if (!inChannelRoutes(route)) { window.__discoverStay = false; }
                if (!targetsChannel(route)) { refreshConfig(false); return; }
                // Channel open: always use a fresh value of the setting (never the one cached in memory).
                refreshConfig(true).then(function (on) {
                    if (on !== true || routeOf(location.href) !== route) { log('config=' + on + ' -> keeping channel tiles'); return; }
                    if (shouldRedirect(route, e.navigationType, from, true)) { redirect(); }
                });
            } catch (err) { }
        });
    }
    // Without the Navigation API (older engines) hashchange is the only signal; it also cannot tell Back apart,
    // so we only redirect when the previous route was outside the channel.
    // Emby changes pages with history.pushState, which fires no hashchange. Without the Navigation API
    // (e.g. LG webOS 6 / Chrome 79, Firefox) the timer at the bottom therefore checks every second whether the route changed.
    var hasNavApi = !!(window.navigation && typeof navigation.addEventListener === 'function');
    window.addEventListener('hashchange', function () { if (!hasNavApi) { onRouteChange(); } });
    function onRouteChange() {
        var route = routeOf(location.href);
        if (route === prevRoute) { return; }
        var from = prevRoute;
        prevRoute = route;
        if (!inChannelRoutes(route)) { window.__discoverStay = false; }
        Promise.all([refreshConfig(true), loadInfo()]).then(function (r) {
            if (r[0] === true && routeOf(location.href) === route && shouldRedirect(route, 'traverse', from, true)) { redirect(); }
        });
    }

    // Safety net next to the Navigation API: Firefox (Android) does not always report the move to the channel in a way
    // that makes the navigate event above redirect. So every second we also check ourselves whether the route just went to the
    // channel from a page outside it (a tap on a tile or menu, not Back from a folder).
    var pollRoute = null;
    function pollCheck() {
        var route = routeOf(location.href);
        if (pollRoute === null) { pollRoute = route; return; }
        if (route === pollRoute) { return; }
        var from = pollRoute;
        pollRoute = route;
        if (!targetsChannel(route) || inChannelRoutes(from)) { return; }
        refreshConfig(true).then(function (on) {
            if (on === true && routeOf(location.href) === route && shouldRedirect(route, 'push', from, true)) { log('safety net: redirect'); redirect(); }
        });
    }

    // ---- Backdrop for the "Discover Media" tile (like Emby does for other libraries) ----
    // Only when the user has "Enable backdrops" on in Emby. The image goes into Emby's own
    // backdrop layer (.backdropContainer), so Emby's dimming simply covers it.
    var bd = { urls: null, loading: null, on: false, timer: null, layer: null, addedClass: false, seenEmby: false, pref: null, prefAsked: false, last: '', seq: 0, page: null, items: {} };
    function bdPrefFromStorage() {
        try {
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                if (k && /enablebackdrops/i.test(k)) {
                    var v = String(localStorage.getItem(k));
                    if (v === 'true') { return true; }
                    if (v === 'false') { return false; }
                }
            }
        } catch (e) { }
        return null;
    }
    function bdAskServerPref() {
        var a = api();
        if (!a || bd.prefAsked) { return; }
        bd.prefAsked = true;
        try {
            Promise.resolve(a.getJSON(a.getUrl('DisplayPreferences/usersettings', { userId: a.getCurrentUserId(), client: 'emby' }))).then(function (p) {
                var v = p && p.CustomPrefs ? p.CustomPrefs.enableBackdrops : undefined;
                if (v === 'true' || v === true) { bd.pref = true; } else if (v === 'false' || v === false) { bd.pref = false; }
            }, function () { });
        } catch (e) { }
    }
    function bdAllowed() {
        var s = bdPrefFromStorage();
        if (s !== null) { return s; }
        if (bd.pref !== null) { return bd.pref; }
        return bd.seenEmby;   // unknown: only if Emby itself already shows backdrops
    }
    function bdLoadUrls() {
        if (bd.urls) { return Promise.resolve(bd.urls); }
        if (bd.loading) { return bd.loading; }
        var a = api();
        if (!a) { return Promise.resolve([]); }
        bd.loading = Promise.resolve(a.getJSON(a.getUrl('Discover/Backdrops'))).then(function (r) {
            bd.urls = (r && r.Urls) || [];
            bd.loading = null;
            return bd.urls;
        }, function () { bd.loading = null; return []; });
        return bd.loading;
    }
    function bdStyle() {
        if (document.getElementById('dcHomeBackdropStyle')) { return; }
        var st = document.createElement('style');
        st.id = 'dcHomeBackdropStyle';
        st.textContent = '.dcHomeBackdrop{position:absolute;top:0;left:0;right:0;bottom:0;background-size:cover;background-position:center center;background-repeat:no-repeat;opacity:0;transition:opacity .6s ease;pointer-events:none;}.dcHomeBackdrop.on{opacity:1;}';
        document.head.appendChild(st);
    }
    var bdObserved = null;
    function bdWatch(container) {
        if (!container || bdObserved === container || typeof MutationObserver !== 'function') { return; }
        bdObserved = container;
        new MutationObserver(function (muts) {
            for (var i = 0; i < muts.length; i++) {
                for (var j = 0; j < muts[i].addedNodes.length; j++) {
                    var n = muts[i].addedNodes[j];
                    if (n !== bd.layer && n.nodeType === 1) { bd.seenEmby = true; }
                }
            }
            // On a channel page with Emby's own backdrop: let Emby win. Otherwise Emby and we fight
            // over the layer and the withBackdrop class, and the top bar flickers now and then.
            if (bd.page && embyHasImage(container)) {
                if (bd.layer && bd.layer.parentNode === container) { container.removeChild(bd.layer); }
                bd.yielded = true;
                return;
            }
            if (bd.yielded && bd.page) { return; }   // this page belongs to Emby: do not restore
            // Emby clears its backdrop on a tile without its own image: then we put ours back.
            if (bd.on && bd.layer && bd.layer.parentNode !== container) { container.appendChild(bd.layer); }
        }).observe(container, { childList: true });
        var bg = document.querySelector('.backgroundContainer');
        if (bg) {
            new MutationObserver(function () {
                if (bd.on && bd.layer && bd.layer.parentNode && !bg.classList.contains('withBackdrop')) { bg.classList.add('withBackdrop'); bd.addedClass = true; }
            }).observe(bg, { attributes: true, attributeFilter: ['class'] });
        }
    }
    // Does Emby itself show a real backdrop image? (An empty or fading element does not count: that wrongly hid ours.)
    function embyHasImage(container) {
        for (var k = 0; k < container.children.length; k++) {
            var ch = container.children[k];
            if (ch === bd.layer) { continue; }
            var bg = (ch.style && ch.style.backgroundImage) || '';
            if (!bg || bg === 'none') { try { bg = getComputedStyle(ch).backgroundImage || ''; } catch (e) { bg = ''; } }
            var hasImg = (bg && bg !== 'none' && bg.indexOf('url(') !== -1) || !!(ch.querySelector && ch.querySelector('img[src]'));
            if (!hasImg) { continue; }
            var op = 1;
            try { op = parseFloat(getComputedStyle(ch).opacity); } catch (e) { }
            if (isNaN(op) || op > 0.05) { return true; }
        }
        return false;
    }
    function bdShow(fixedUrl) {
        var container = document.querySelector('.backdropContainer');
        if (!container) { return; }
        bdWatch(container);
        if (!bdAllowed()) { return; }
        // Channel page where Emby already shows its own backdrop: do nothing.
        if (bd.page) {
            if (embyHasImage(container)) { return; }
        }
        bd.on = true;
        var token = ++bd.seq;
        (fixedUrl ? Promise.resolve([fixedUrl]) : bdLoadUrls()).then(function (urls) {
            if (!bd.on || token !== bd.seq || !urls.length) { return; }
            var pick = urls[Math.floor(Math.random() * urls.length)];
            if (urls.length > 1 && pick === bd.last) { pick = urls[(urls.indexOf(pick) + 1) % urls.length]; }
            if (fixedUrl && pick === bd.last && bd.layer && bd.layer.parentNode && bd.layer.classList.contains('on')) { return; }
            bd.last = pick;
            var img = new Image();
            img.onload = function () {
                if (!bd.on || token !== bd.seq) { return; }
                bdStyle();
                if (!bd.layer) { bd.layer = document.createElement('div'); bd.layer.className = 'dcHomeBackdrop'; }
                bd.layer.classList.remove('on');
                bd.layer.style.backgroundImage = 'url("' + pick + '")';
                if (bd.layer.parentNode !== container) { container.appendChild(bd.layer); }
                var bg = document.querySelector('.backgroundContainer');
                if (bg && !bg.classList.contains('withBackdrop')) { bg.classList.add('withBackdrop'); bd.addedClass = true; }
                requestAnimationFrame(function () { if (bd.on && bd.layer) { bd.layer.classList.add('on'); } });
            };
            img.src = pick;
        });
    }
    function bdHide() {
        clearTimeout(bd.timer);
        if (!bd.on) { return; }
        bd.on = false;
        bd.seq++;
        var layer = bd.layer;
        if (layer) { layer.classList.remove('on'); }
        setTimeout(function () {
            if (bd.on) { return; }
            if (layer && layer.parentNode) { layer.parentNode.removeChild(layer); }
            if (bd.addedClass) {
                var container = document.querySelector('.backdropContainer');
                var bg = document.querySelector('.backgroundContainer');
                if (bg && (!container || !container.children.length)) { bg.classList.remove('withBackdrop'); }
                bd.addedClass = false;
            }
        }, 650);
    }
    function bdCardId(el) {
        var card = el && el.closest ? el.closest('.card, [data-id]') : null;
        if (!card) { return null; }
        var id = card.getAttribute('data-id');
        if (!id) {
            var link = card.querySelector('a[href*="id="]') || (card.tagName === 'A' ? card : null);
            var m = link && /[?&](?:parent)?id=([0-9a-z]+)/i.exec(link.getAttribute('href') || '');
            id = m ? m[1] : '';
        }
        return String(id || '').toLowerCase();
    }
    // Backdrop for an Emby item id from the channel: { InChannel, Url } (cached per id).
    function bdItem(id) {
        var a = api();
        if (!a || !id) { return Promise.resolve(null); }
        if (bd.items[id]) { return bd.items[id]; }
        var p = Promise.resolve(a.getJSON(a.getUrl('Discover/ItemBackdrop', { Id: id }))).then(function (r) { return r || null; }, function () { delete bd.items[id]; return null; });
        bd.items[id] = p;
        return p;
    }
    function bdOnPointer(e) {
        var id = bdCardId(e.target);
        if (id === null) { return; }             // no card: change nothing
        if (bd.page) {
            // In the channel: the backdrop follows the title under the mouse (like in an Emby library).
            if (id === bd.hoverId) { return; }
            bd.hoverId = id;
            var page = bd.page;
            clearTimeout(bd.timer);
            bd.timer = setTimeout(function () {
                bdItem(id).then(function (r) {
                    if (bd.page !== page || bd.hoverId !== id) { return; }
                    if (r && r.InChannel && r.Url) { bdShow(r.Url); }
                });
            }, 150);
            return;
        }
        if (tvLayout()) { return; }               // TV: Emby's own focus preview handles it (see tvPatchBackdrop)
        if (info && info.ids.indexOf(id) !== -1) {
            if (bd.on) { return; }                // already visible: no new image for every child element
            bdAskServerPref();
            clearTimeout(bd.timer);
            bd.timer = setTimeout(bdShow, 120);
        } else {
            bdHide();                             // another tile: Emby shows its own backdrop
        }
    }
    // Pages in the channel (root, rows, title folder, request folder, season, trailer) get a backdrop:
    // the title itself if there is one, otherwise a random trending one.
    function bdRouteId(route) {
        var m = /^\/(?:list|videos|tv|movies)\b.*[?&]parentid=([^&]+)/.exec(route) || /^\/(?:item|details|channel)\b.*[?&]id=([^&]+)/.exec(route);
        return m ? decodeURIComponent(m[1]).toLowerCase() : null;
    }
    function bdCheckPage() {
        var route = routeOf(location.href);
        var id = bdRouteId(route);
        if (bd.page && bd.page.route === route) { return; }
        if (!id) {
            if (bd.page) { bd.page = null; bd.hoverId = null; bdHide(); }
            return;
        }
        var page = { route: route };
        bd.page = page;
        bd.hoverId = null;
        if (info && info.ids.indexOf(id) !== -1) { bdAskServerPref(); setTimeout(function () { if (bd.page === page) { bdShow(); } }, 250); return; }
        bdItem(id).then(function (r) {
            if (bd.page !== page) { return; }
            if (!r || !r.InChannel) { bd.page = null; bdHide(); return; }
            bdAskServerPref();
            setTimeout(function () { if (bd.page === page) { bdShow(r.Url || null); } }, 250);
        });
    }
    function bdOnNavigate() {
        bd.yielded = false;
        bdHide();
        bd.page = null;
        bd.hoverId = null;
        setTimeout(bdCheckPage, 0);
    }
    document.addEventListener('mouseover', bdOnPointer, true);
    document.addEventListener('focusin', bdOnPointer, true);
    if (window.navigation && typeof navigation.addEventListener === 'function') { navigation.addEventListener('navigatesuccess', bdOnNavigate); }
    else { window.addEventListener('hashchange', bdOnNavigate); }


    // ---- TV layout (Android TV / Chromecast / Google TV) ----
    function tvLayout() { var d = document.documentElement; return !!(d && d.classList && d.classList.contains('layout-tv')); }

    // 1. On TV Emby shows a backdrop for the focused tile itself (focus preview). For the channel
    //    "Discover Media" Emby finds no image and clears the backdrop. We hook into Emby's own
    //    backdrop module: for the Discover tile it gets a trending image from Discover.
    var tvBd = { patched: false, seq: 0 };
    function pickBackdrop(urls) {
        var pick = urls[Math.floor(Math.random() * urls.length)];
        if (urls.length > 1 && pick === bd.last) { pick = urls[(urls.indexOf(pick) + 1) % urls.length]; }
        bd.last = pick;
        return pick;
    }
    function tvPatchBackdrop() {
        if (tvBd.patched || !window.Emby || typeof Emby.importModule !== 'function') { return; }
        tvBd.patched = true;
        Promise.resolve(Emby.importModule('./modules/backdrop/backdrop.js')).then(function (m) {
            var bm = m && (m.default || m);
            if (!bm || typeof bm.setBackdrop !== 'function' || bm.__dcPatched) { return; }
            bm.__dcPatched = true;
            var orig = bm.setBackdrop;
            bm.setBackdrop = function (item) {
                var my = ++tvBd.seq;
                try {
                    var id = item && typeof item === 'object' && item.Id ? String(item.Id).toLowerCase() : null;
                    var own = item && item.BackdropImageTags && item.BackdropImageTags.length;
                    if (id && !own && info && info.ids.indexOf(id) !== -1) {
                        var self = this;
                        if (bd.urls && bd.urls.length) { return orig.call(self, pickBackdrop(bd.urls)); }
                        bdLoadUrls().then(function (u) { if (my === tvBd.seq && u.length) { orig.call(self, pickBackdrop(u)); } });
                    }
                } catch (e) { }
                return orig.apply(this, arguments);
            };
            log('tv: backdrop hooked to the Discover tile');
        }, function () { tvBd.patched = false; });
    }

    // 2. Discover on TV is designed for 1920 css pixels wide (like LG webOS). Android TV / Chromecast
    //    uses 960x540 css pixels, which made everything in Discover twice as large. We scale the
    //    Discover page (.dcTvPage) back to that 1920 size, with the same text ratio as Emby.
    // Discover also uses vh/vw (e.g. 100vh, 88vh). Inside zoom those are wrong, so in
    // Discover's own <style> we rewrite every vh/vw to a variable that gives the 1920x1080 size.
    var VUNIT = /(-?\d*\.?\d+)(d?vh|vw)\b/g;
    function tvStyles(on) {
        var list = document.querySelectorAll('style');
        for (var i = 0; i < list.length; i++) {
            var st = list[i];
            if (st.id === 'dcTvScaleStyle') { continue; }
            if (st.__dcOrig === undefined) {
                var t = st.textContent || '';
                if (t.indexOf('.dcRoot') === -1 || t.indexOf('.dcTvPage') === -1) { continue; }
                st.__dcOrig = t;
                st.__dcScaled = t.replace(VUNIT, function (m, n, u) { return 'calc(' + n + ' * var(' + (u === 'vw' ? '--dcVw' : '--dcVh') + '))'; });
            }
            var want = on ? st.__dcScaled : st.__dcOrig;
            if (st.textContent !== want) { st.textContent = want; }
        }
    }
    function tvScale() {
        var d = document.documentElement;
        if (!d) { return; }
        if (!document.getElementById('dcTvScaleStyle')) {
            var st = document.createElement('style');
            st.id = 'dcTvScaleStyle';
            st.textContent = 'html.layout-tv.dcTvScaled .dcTvPage{zoom:var(--dcTvZoom);font-size:var(--dcTvFont);}';
            (document.head || d).appendChild(st);
        }
        var w = window.innerWidth || 0, h = window.innerHeight || 0;
        if (!tvLayout() || !w || !h || w >= 1600) {
            if (d.classList.contains('dcTvScaled')) { d.classList.remove('dcTvScaled'); tvStyles(false); }
            return;
        }
        var z = Math.round(w / 1920 * 1000) / 1000;
        var rootPx = 16;
        try { rootPx = parseFloat(getComputedStyle(d).fontSize) || 16; } catch (e) { }
        d.style.setProperty('--dcTvZoom', String(z));
        d.style.setProperty('--dcTvFont', (rootPx / z).toFixed(2) + 'px');
        d.style.setProperty('--dcVh', (h / 100 / z).toFixed(3) + 'px');
        d.style.setProperty('--dcVw', (w / 100 / z).toFixed(3) + 'px');
        if (!d.classList.contains('dcTvScaled')) { d.classList.add('dcTvScaled'); log('tv: Discover scaled x' + z + ' (screen ' + w + 'x' + h + ' css px)'); }
        tvStyles(true);
    }
    // Scale as soon as the Discover page appears (not only after the 1 s timer).
    var tvMoPending = false;
    if (typeof MutationObserver === 'function') {
        new MutationObserver(function () {
            if (tvMoPending || !tvLayout()) { return; }
            tvMoPending = true;
            (window.requestAnimationFrame || setTimeout)(function () { tvMoPending = false; tvScale(); });
        }).observe(document.documentElement, { childList: true, subtree: true });
    }
    window.addEventListener('resize', tvScale);

    log('loader active (v' + 4 + '); check in the console with: __discoverDebug()');
    tvScale();
    setInterval(function () {
        tvScale();
        if (tvLayout()) { tvPatchBackdrop(); }
        var a = api();
        if (!a) { return; }
        refreshConfig(false);
        if (!hasNavApi && initialChecked) { onRouteChange(); }
        if (hasNavApi && initialChecked) { pollCheck(); }
        loadInfo().then(function (i) {
            if (!initialChecked && i && redirectOn !== null) {
                initialChecked = true;
                var r0 = routeOf(location.href);
                if (!prevRoute) { prevRoute = r0; }
                if (shouldRedirect(r0, 'push', '')) { log('channel is open, redirecting'); redirect(); }
            }
            if (!buttonActive() && !buttonTried) { buttonTried = true; loadButton(a, (i && i.buttonPages) || KNOWN_BTN, 0); }
            if (i && !bd.pageChecked) { bd.pageChecked = true; bdCheckPage(); }
            if (i && i.ids.length && tvLayout() && !bd.urls) { bdLoadUrls(); }
        });
    }, 1000);
})();