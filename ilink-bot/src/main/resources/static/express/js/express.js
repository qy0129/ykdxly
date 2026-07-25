(function () {
  'use strict';

  var mapData = window.__MAP_DATA__ || {};
  var points = Array.isArray(mapData.points) ? mapData.points : [];
  var hasRoute = points.length >= 2;
  var mapWrap = document.getElementById('mapWrap');
  var mapContainer = document.getElementById('mapContainer');
  var startLabel = document.getElementById('startLabel');
  var endLabel = document.getElementById('endLabel');
  var fill = document.getElementById('progressFill');
  var truck = document.getElementById('progressTruck');
  var startAddress = document.getElementById('startAddress');
  var endAddress = document.getElementById('endAddress');
  var startAddressTitle = document.getElementById('startAddressTitle');
  var endAddressTitle = document.getElementById('endAddressTitle');
  if (!mapWrap || !mapContainer || !startLabel || !endLabel || !fill || !truck
      || !startAddress || !endAddress || !startAddressTitle || !endAddressTitle) {
    return;
  }

  mapWrap.hidden = false;
  var start = points[0];
  var current = points[points.length - 1];
  var end = points[points.length - 1];
  startLabel.textContent = start ? '发货地：' + displayName(start) : '暂无可定位节点';
  endLabel.textContent = end ? '在途地点：' + displayName(end) : '等待物流更新';
  renderAddresses();

  function displayName(point) {
    return point.area || point.context || '物流节点';
  }

  function addressText(point) {
    if (!point) {
      return '暂无地址信息';
    }
    if (point.area && point.context) {
      return point.area + ' · ' + point.context;
    }
    return point.area || point.context || '暂无地址信息';
  }

  function renderAddresses() {
    if (!points.length) {
      startAddress.textContent = '暂无地址信息';
      endAddress.textContent = '暂无地址信息';
      return;
    }
    if (!hasRoute) {
      startAddressTitle.textContent = '当前地址';
      endAddressTitle.textContent = '物流状态';
      startAddress.textContent = addressText(current);
      endAddress.textContent = current.time || '暂无更新时间';
      return;
    }
    startAddress.textContent = addressText(start);
    endAddress.textContent = addressText(end);
  }

  function setProgress() {
    fill.style.width = hasRoute ? '100%' : '0%';
    truck.style.display = hasRoute ? 'block' : 'none';
    truck.style.left = '100%';
  }

  function createFallbackMap() {
    var fallback = document.createElement('div');
    fallback.className = 'route-fallback';
    if (!points.length) {
      fallback.innerHTML = '<p class="map-empty">暂无可定位物流节点</p>';
      mapContainer.replaceChildren(fallback);
      return;
    }
    points.forEach(function (point, index) {
      var node = document.createElement('div');
      node.className = 'route-node' + (index === points.length - 1 ? ' current' : '');
      node.style.left = hasRoute ? (index / (points.length - 1) * 100) + '%' : '50%';
      var symbol = hasRoute && index === 0 ? '📍' : index === points.length - 1 ? '🚚' : '●';
      node.innerHTML = '<span>' + symbol + '</span><small>' + escapeHtml(displayName(point)) + '</small>';
      fallback.appendChild(node);
    });
    mapContainer.replaceChildren(fallback);
  }

  function escapeHtml(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function createMap() {
    if (!window.BMap || !window.BMap.Map) {
      createFallbackMap();
      return;
    }

    var map = new window.BMap.Map('mapContainer');
    map.enableScrollWheelZoom(true);
    resolveMapPoints(function (resolved) {
      if (!resolved.length) {
        map.centerAndZoom(new window.BMap.Point(105, 35), 4);
        showMapMessage(mapContainer, '暂无可定位物流地址');
        return;
      }
      var origin = resolved[0];
      var currentPosition = resolved[resolved.length - 1];
      if (resolved.length === 1) {
        addMarker(map, currentPosition.point, '🚚', displayName(currentPosition.data), 'truck');
        map.centerAndZoom(currentPosition.point, 11);
        return;
      }
      addMarker(map, origin.point, '📍', displayName(origin.data), 'start');
      addMarker(map, currentPosition.point, '🚚', displayName(currentPosition.data), 'truck');
      drawRoadRoute(map, origin.point, currentPosition.point);
    });
  }

  function resolveMapPoints(done) {
    if (!points.length) {
      done([]);
      return;
    }
    var geocoder = new window.BMap.Geocoder();
    var resolved = [];
    var index = 0;
    function next() {
      if (index >= points.length) {
        done(resolved);
        return;
      }
      var data = points[index++];
      if (typeof data.lng === 'number' && typeof data.lat === 'number') {
        resolved.push({ data: data, point: new window.BMap.Point(data.lng, data.lat) });
        next();
        return;
      }
      var query = data.area || data.context;
      if (!query) {
        next();
        return;
      }
      geocoder.getPoint(query, function (point) {
        if (point) {
          resolved.push({ data: data, point: point });
        }
        next();
      }, data.area || '');
    }
    next();
  }

  function showMapMessage(container, message) {
    var hint = document.createElement('p');
    hint.className = 'map-empty';
    hint.textContent = message;
    container.appendChild(hint);
  }

  function drawRoadRoute(map, origin, currentPosition) {
    var driving = new window.BMap.DrivingRoute(map, {
      onSearchComplete: function (result) {
        if (driving.getStatus() !== window.BMAP_STATUS_SUCCESS || !result.getPlan(0)) {
          drawKnownNodeLine(map, [origin, currentPosition]);
          return;
        }
        var route = result.getPlan(0).getRoute(0);
        var path = route && route.getPath ? route.getPath() : [];
        if (!path.length) {
          drawKnownNodeLine(map, [origin, currentPosition]);
          return;
        }
        map.addOverlay(new window.BMap.Polyline(path, {
          strokeColor: '#e53935', strokeWeight: 6, strokeOpacity: 0.9
        }));
        map.setViewport(path, { margins: [80, 60, 100, 60] });
      }
    });
    driving.search(origin, currentPosition);
  }

  function drawKnownNodeLine(map, mapPoints) {
    map.addOverlay(new window.BMap.Polyline(mapPoints, {
      strokeColor: '#e53935', strokeWeight: 6, strokeOpacity: 0.9, strokeStyle: 'dashed'
    }));
    map.setViewport(mapPoints, { margins: [80, 60, 100, 60] });
  }

  function addMarker(map, point, symbol, label, type) {
    var marker = new window.BMap.Marker(point);
    var tag = new window.BMap.Label('<span class="map-marker ' + type + '">' + symbol + '</span><span class="map-marker-label">' + escapeHtml(label) + '</span>', {
      offset: new window.BMap.Size(-28, -46)
    });
    tag.setStyle({ border: '0', background: 'transparent', color: '#fff', padding: '0' });
    marker.setLabel(tag);
    map.addOverlay(marker);
  }

  function loadMapApi() {
    var ak = (window.__EXPRESS__ || {}).baiduAk;
    if (!ak) {
      createFallbackMap();
      return;
    }
    window.__initExpressMap = createMap;
    var script = document.createElement('script');
    script.src = 'https://api.map.baidu.com/api?v=3.0&ak=' + encodeURIComponent(ak) + '&callback=__initExpressMap';
    script.onerror = createFallbackMap;
    document.head.appendChild(script);
  }

  requestAnimationFrame(setProgress);
  loadMapApi();
  document.getElementById('fullscreenBtn').addEventListener('click', function () {
    mapWrap.classList.toggle('map-fullscreen');
  });
}());
