(function () {
  'use strict';

  var status = document.getElementById('status');
  var detail = document.getElementById('detail');
  var retry = document.getElementById('retry');
  var running = false;

  function token() {
    var parts = window.location.pathname.split('/');
    return parts[parts.length - 1] || '';
  }

  function showError(message, extra) {
    status.textContent = message;
    detail.textContent = extra || '';
    retry.hidden = false;
    retry.disabled = false;
    running = false;
  }

  function requestLocation() {
    if (running) return;
    if (!window.isSecureContext || !navigator.geolocation) {
      showError('当前浏览器无法使用定位', '请使用手机浏览器打开 HTTPS 定位链接。');
      return;
    }
    running = true;
    retry.hidden = true;
    retry.disabled = true;
    status.textContent = '正在请求手机定位权限…';
    detail.textContent = '';

    navigator.geolocation.getCurrentPosition(submitLocation, function (error) {
      if (error.code === error.PERMISSION_DENIED) {
        showError('未获得定位权限', '请在浏览器设置中允许位置权限后重试。');
      } else if (error.code === error.TIMEOUT) {
        showError('定位超时', '请确认手机已开启定位服务。');
      } else {
        showError('暂时无法获取位置', '请检查网络和手机定位设置。');
      }
    }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 });
  }

  function submitLocation(position) {
    status.textContent = '正在确认位置…';
    fetch('/location/api/' + encodeURIComponent(token()), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy
      })
    }).then(function (response) {
      return response.json().then(function (data) {
        return { ok: response.ok, data: data };
      });
    }).then(function (result) {
      if (!result.ok || !result.data.success) {
        showError(result.data.message || '位置更新失败');
        return;
      }
      status.textContent = '位置已更新';
      detail.textContent = result.data.address || '';
      running = false;
    }).catch(function () {
      showError('位置提交失败', '请检查网络后重试。');
    });
  }

  retry.addEventListener('click', requestLocation);
  requestLocation();
}());
