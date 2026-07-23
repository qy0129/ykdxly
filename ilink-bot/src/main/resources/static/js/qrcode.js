(function () {
  'use strict';

  // ===== Config =====
  var TOTAL = Number(window.QR_EXPIRE_SECONDS) || 300, remaining = TOTAL, timer = null;
  var angerClickCount = 0, angerTimer = null, isAnger = false, isRobotBusy = false;
  var lastClickTime = 0;

  // ===== DOM refs =====
  var termEl = document.getElementById('termBody');
  var cdEl = document.getElementById('countdown');
  var pbEl = document.getElementById('pbar');
  var ovEl = document.getElementById('overlay');
  var qrTrigger = document.getElementById('qrTrigger');
  var qrImage = document.getElementById('qrImage');
  var qrModal = document.getElementById('qrModal');
  var qrModalImage = document.getElementById('qrModalImage');
  var qrModalClose = document.getElementById('qrModalClose');
  var robotSvg = document.querySelector('.robot-svg');
  var hexEl = document.getElementById('hexDisplay');

  // ===== Terminal log pool =====
  var logPool = [
    '> [INFO] SCAN REQUEST RECEIVED',
    '> [OK] QR CODE DECODED',
    '> [SYS] USER AGENT VERIFIED',
    '> [WARN] CONNECTION LATENCY 42MS',
    '> [DATA] PACKET 0x7F RECEIVED',
    '> [AUTH] CREDENTIALS VALIDATED',
    '> [INFO] SESSION TOKEN REFRESHED',
    '> [OK] HEARTBEAT ACK',
    '> [SYS] MEMORY USAGE 34.2%',
    '> [WARN] RATE LIMIT APPROACHING',
    '> [DATA] STREAM BUFFER FLUSHED',
    '> [AUTH] ENCRYPTION HANDSHAKE OK'
  ];

  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = a[i]; a[i] = a[j]; a[j] = t;
    }
    return a;
  }

  // ===== Terminal typewriter =====
  var defaultLogs = [
    '> INITIALIZING WECHAT LINK...',
    '> QR MODULE V3.2 LOADED',
    '> NEURAL AUTH PROTOCOL ACTIVE',
    '> SCAN VECTOR READY',
    '> ENCRYPTED CHANNEL STANDBY',
    '> AWAITING INPUT...'
  ];

  function typeLine(txt, cb) {
    var d = document.createElement('div');
    d.className = 'line';
    termEl.appendChild(d);
    var i = 0;
    function n() {
      if (i < txt.length) {
        d.textContent += txt[i];
        i++;
        setTimeout(n, 12 + Math.random() * 16);
      } else {
        if (cb) setTimeout(cb, 300);
      }
    }
    n();
  }

  function runTerm(logs, cb) {
    termEl.innerHTML = '';
    var idx = 0;
    function next() {
      if (idx < logs.length) {
        typeLine(logs[idx], next);
        idx++;
      } else {
        var c = document.createElement('span');
        c.className = 'cursor';
        termEl.appendChild(c);
        if (cb) cb();
      }
    }
    next();
  }

  // ===== Countdown =====
  function pad(n) { return String(n).padStart(2, '0'); }
  function fmt(s) { return pad(Math.floor(s / 3600)) + ':' + pad(Math.floor((s % 3600) / 60)) + ':' + pad(s % 60); }

  function upd() {
    if (cdEl) {
      cdEl.textContent = fmt(remaining);
      cdEl.className = 'countdown' + (remaining <= 30 ? ' warn' : '');
    }
    if (pbEl) pbEl.style.width = (remaining / TOTAL * 100) + '%';
  }

  function expire() {
    if (timer) { clearInterval(timer); timer = null; }
    if (ovEl) ovEl.classList.add('show');
    // 到期立即收起放大层，避免用户继续扫描已经失效的二维码。
    if (qrModal) {
      qrModal.classList.remove('is-open');
      qrModal.setAttribute('aria-hidden', 'true');
    }
  }

  function tick() { remaining--; upd(); if (remaining <= 0) expire(); }

  function startCD() {
    if (timer) clearInterval(timer);
    remaining = TOTAL;
    if (ovEl) ovEl.classList.remove('show');
    if (cdEl) cdEl.className = 'countdown';
    upd();
    timer = setInterval(tick, 1000);
  }

  // ===== QR Zoom =====
  function closeQrModal() {
    if (!qrModal) return;
    qrModal.classList.remove('is-open');
    qrModal.setAttribute('aria-hidden', 'true');
    if (qrTrigger) qrTrigger.focus();
  }

  function openQrModal() {
    if (!qrTrigger || !qrImage || !qrModal || !qrModalImage) return;
    // 过期二维码保持不可点击，避免用户在放大层扫描失效内容。
    if (ovEl && ovEl.classList.contains('show')) return;
    qrModalImage.src = qrImage.src;
    qrModal.classList.add('is-open');
    qrModal.setAttribute('aria-hidden', 'false');
    qrTrigger.classList.remove('is-active');
    void qrTrigger.offsetWidth;
    qrTrigger.classList.add('is-active');
    if (qrModalClose) qrModalClose.focus();
  }

  if (qrTrigger) qrTrigger.addEventListener('click', openQrModal);
  if (qrModalClose) qrModalClose.addEventListener('click', closeQrModal);
  if (qrModal) qrModal.addEventListener('click', function (event) {
    if (event.target === qrModal) closeQrModal();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && qrModal && qrModal.classList.contains('is-open')) closeQrModal();
  });

  // ===== Ambient Click Feedback =====
  function isInteractiveTarget(target) {
    return target.closest('.qr-box, .robot-wrap, .term-box, .qr-modal, button, a, input, textarea, select');
  }

  document.addEventListener('click', function (event) {
    // 尊重系统的“减少动态效果”设置，避免禁用动画后临时元素残留在页面上。
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches
      || isAnger || (qrModal && qrModal.classList.contains('is-open')) || isInteractiveTarget(event.target)) return;
    var tap = document.createElement('div');
    tap.className = 'ambient-tap';
    tap.style.left = event.clientX + 'px';
    tap.style.top = event.clientY + 'px';
    document.body.appendChild(tap);
    tap.addEventListener('animationend', function () { tap.remove(); });
  });

  // ===== Grid Canvas Mouse Trail =====
  function initGridTrail() {
    if ('ontouchstart' in window) return;
    var canvas = document.createElement('canvas');
    canvas.id = 'gridCanvas';
    document.body.insertBefore(canvas, document.body.firstChild);
    var ctx = canvas.getContext('2d');
    var W, H, trail = [];

    function resize() {
      W = window.innerWidth;
      H = window.innerHeight;
      canvas.width = W;
      canvas.height = H;
    }
    resize();
    window.addEventListener('resize', resize);

    function drawGrid() {
      ctx.clearRect(0, 0, W, H);
      var baseColor = isAnger ? '255,45,45' : '0,229,232';
      var gridSize = 48;
      for (var x = 0; x < W; x += gridSize) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, H);
        ctx.strokeStyle = 'rgba(' + baseColor + ',0.04)';
        ctx.stroke();
      }
      for (var y = 0; y < H; y += gridSize) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(W, y);
        ctx.strokeStyle = 'rgba(' + baseColor + ',0.04)';
        ctx.stroke();
      }
      // trail
      var now = Date.now();
      trail = trail.filter(function (t) { return now - t.time < 800; });
      var radius = 80;
      for (var i = 0; i < trail.length; i++) {
        var age = now - trail[i].time;
        var alpha = (1 - age / 800) * 0.8;
        var t = trail[i];
        var gx = Math.round(t.x / gridSize) * gridSize;
        var gy = Math.round(t.y / gridSize) * gridSize;
        var c = 'rgba(' + baseColor + ',';
        // horizontal segment
        var hL = Math.max(t.x - radius, 0);
        var hR = Math.min(t.x + radius, W);
        var hGrad = ctx.createLinearGradient(hL, gy, hR, gy);
        hGrad.addColorStop(0, c + '0)');
        hGrad.addColorStop(0.25, c + alpha + ')');
        hGrad.addColorStop(0.75, c + alpha + ')');
        hGrad.addColorStop(1, c + '0)');
        ctx.beginPath();
        ctx.moveTo(hL, gy);
        ctx.lineTo(hR, gy);
        ctx.strokeStyle = hGrad;
        ctx.stroke();
        // vertical segment
        var vT = Math.max(t.y - radius, 0);
        var vB = Math.min(t.y + radius, H);
        var vGrad = ctx.createLinearGradient(gx, vT, gx, vB);
        vGrad.addColorStop(0, c + '0)');
        vGrad.addColorStop(0.25, c + alpha + ')');
        vGrad.addColorStop(0.75, c + alpha + ')');
        vGrad.addColorStop(1, c + '0)');
        ctx.beginPath();
        ctx.moveTo(gx, vT);
        ctx.lineTo(gx, vB);
        ctx.strokeStyle = vGrad;
        ctx.stroke();
      }
      requestAnimationFrame(drawGrid);
    }

    document.addEventListener('mousemove', function (e) {
      trail.push({ x: e.clientX, y: e.clientY, time: Date.now() });
      if (trail.length > 60) trail.shift();
    });

    drawGrid();
  }

  // ===== Robot Hit Area Detection =====
  function getHitPart(svgEl, clientX, clientY) {
    var rect = svgEl.getBoundingClientRect();
    var x = (clientX - rect.left) / rect.width * 200;
    var y = (clientY - rect.top) / rect.height * 280;
    // Part definitions using viewBox coordinates
    if (y >= 10 && y <= 48 && x >= 95 && x <= 105) return 'antenna';
    if (y >= 48 && y <= 124 && x >= 55 && x <= 145) return 'head';
    if (y >= 140 && y <= 197 && x >= 40 && x <= 58) return 'left-arm';
    if (y >= 140 && y <= 197 && x >= 142 && x <= 160) return 'right-arm';
    if (y >= 136 && y <= 208 && x >= 62 && x <= 138) return 'body';
    if (y >= 208 && y <= 244 && x >= 72 && x <= 92) return 'left-leg';
    if (y >= 208 && y <= 244 && x >= 108 && x <= 128) return 'right-leg';
    return 'miss';
  }

  // ===== Robot Animation Helpers =====
  function getAnimate(svg, selector, attr) {
    var el = svg.querySelector(selector);
    if (!el) return null;
    return el.querySelector('animate' + (attr === 'transform' ? 'Transform' : ''));
  }

  function setAnim(svg, selector, attr, values, dur) {
    var anim = getAnimate(svg, selector, attr);
    if (anim) {
      anim.setAttribute('values', values);
      anim.setAttribute('dur', dur || '0.8s');
      anim.setAttribute('repeatCount', '1');
      anim.setAttribute('fill', 'freeze');
      anim.beginElement();
    }
  }

  function saveOriginalAnims() {
    robotSvg.querySelectorAll('animate, animateTransform').forEach(function (a) {
      a.dataset.origValues = a.getAttribute('values');
      a.dataset.origDur = a.getAttribute('dur');
    });
  }

  function resetAnims(svg) {
    var anims = svg.querySelectorAll('animate, animateTransform');
    anims.forEach(function (a) {
      a.setAttribute('repeatCount', 'indefinite');
      a.setAttribute('fill', 'remove');
      if (a.dataset.origValues) a.setAttribute('values', a.dataset.origValues);
      if (a.dataset.origDur) a.setAttribute('dur', a.dataset.origDur);
      try { a.beginElement(); } catch (e) {}
    });
    svg.classList.remove('anger-shake');
  }

  function resumeDefault(svg) {
    resetAnims(svg);
    isRobotBusy = false;
  }

  // ===== Robot Actions =====
  var actions = {
    head: function (svg) {
      isRobotBusy = true;
      // head shake: use whole body rotate
      svg.querySelectorAll('animate, animateTransform').forEach(function (a) { a.setAttribute('repeatCount', '0'); });
      var bounce = svg.querySelector('animateTransform');
      if (bounce) bounce.setAttribute('repeatCount', '0');
      // add temp style
      svg.style.transition = 'transform 0.15s ease';
      svg.style.transform = 'translateX(24px) translateY(18px) rotate(-15deg)';
      setTimeout(function () {
        svg.style.transform = 'translateX(24px) translateY(18px) rotate(12deg)';
        setTimeout(function () {
          svg.style.transform = 'translateX(24px) translateY(18px) rotate(0deg)';
          svg.style.transition = '';
          // flash eyes
          var eyes = svg.querySelectorAll('.eye');
          eyes.forEach(function (e) { e.style.fill = '#FFFFFF'; e.setAttribute('r', '8'); });
          setTimeout(function () {
            eyes.forEach(function (e) { e.style.fill = ''; e.setAttribute('r', ''); });
            resumeDefault(svg);
          }, 300);
        }, 150);
      }, 150);
    },
    'left-arm': function (svg) {
      isRobotBusy = true;
      // fast wave 3 times
      var armG = svg.querySelector('g');
      if (armG) {
        var anim = armG.querySelector('animateTransform');
        if (anim) {
          anim.setAttribute('values', '-20,55,150;25,55,150;-20,55,150');
          anim.setAttribute('dur', '0.3s');
          anim.setAttribute('repeatCount', '3');
          anim.setAttribute('fill', 'freeze');
          anim.beginElement();
        }
      }
      setTimeout(function () { resumeDefault(svg); }, 1200);
    },
    'right-arm': function (svg) {
      isRobotBusy = true;
      // punch
      var arms = svg.querySelectorAll('g');
      if (arms.length >= 2) {
        var rightArmG = arms[1];
        var anim = rightArmG.querySelector('animateTransform');
        if (anim) {
          anim.setAttribute('values', '0,145,150;-30,145,150;0,145,150');
          anim.setAttribute('dur', '0.15s');
          anim.setAttribute('repeatCount', '2');
          anim.setAttribute('fill', 'freeze');
          anim.beginElement();
        }
        // body lean
        var bounce = svg.querySelector('animateTransform');
        if (bounce) bounce.setAttribute('repeatCount', '0');
        svg.style.transition = 'transform 0.1s ease';
        svg.style.transform = 'translateX(30px) translateY(18px)';
        setTimeout(function () {
          svg.style.transform = 'translateX(24px) translateY(18px)';
          setTimeout(function () {
            svg.style.transform = 'translateX(18px) translateY(18px)';
            setTimeout(function () {
              svg.style.transform = 'translateX(24px) translateY(18px)';
              svg.style.transition = '';
              resumeDefault(svg);
            }, 100);
          }, 100);
        }, 100);
      }
    },
    body: function (svg) {
      isRobotBusy = true;
      // chest light flash + shake
      var chestLights = svg.querySelectorAll('.light');
      var colors = ['#FF0000', '#0055FF', '#FF0000', '#0055FF', '#FF0000'];
      chestLights.forEach(function (l) {
        l.style.animation = 'none';
        var ci = 0;
        var flash = setInterval(function () {
          if (ci >= colors.length) {
            clearInterval(flash);
            l.style.animation = '';
            isRobotBusy = false;
            return;
          }
          l.style.fill = colors[ci];
          ci++;
        }, 100);
      });
      // shake
      svg.style.transition = 'transform 0.05s ease';
      var shakeCount = 0;
      var shake = setInterval(function () {
        if (shakeCount >= 10) {
          clearInterval(shake);
          svg.style.transform = 'translateX(24px) translateY(18px)';
          svg.style.transition = '';
          resumeDefault(svg);
          return;
        }
        svg.style.transform = 'translateX(' + (24 + (shakeCount % 2 ? 5 : -5)) + 'px) translateY(' + (18 + (shakeCount % 2 ? 3 : -3)) + 'px)';
        shakeCount++;
      }, 50);
    },
    'left-leg': function (svg) {
      isRobotBusy = true;
      var legs = svg.querySelectorAll('g');
      if (legs.length >= 4) {
        var leftLeg = legs[2];
        var anim = leftLeg.querySelector('animateTransform');
        if (anim) {
          anim.setAttribute('values', '0,82,208;-25,82,208;0,82,208');
          anim.setAttribute('dur', '0.25s');
          anim.setAttribute('repeatCount', '3');
          anim.setAttribute('fill', 'freeze');
          anim.beginElement();
        }
        // Opp arm swing
        var rightArm = legs[1];
        var raAnim = rightArm.querySelector('animateTransform');
        if (raAnim) {
          raAnim.setAttribute('values', '0,145,150;15,145,150;0,145,150');
          raAnim.setAttribute('dur', '0.25s');
          raAnim.setAttribute('repeatCount', '3');
          raAnim.setAttribute('fill', 'freeze');
          raAnim.beginElement();
        }
      }
      setTimeout(function () { resumeDefault(svg); }, 1500);
    },
    'right-leg': function (svg) {
      isRobotBusy = true;
      var legs = svg.querySelectorAll('g');
      if (legs.length >= 4) {
        var rightLeg = legs[3];
        var anim = rightLeg.querySelector('animateTransform');
        if (anim) {
          anim.setAttribute('values', '0,118,208;25,118,208;0,118,208');
          anim.setAttribute('dur', '0.25s');
          anim.setAttribute('repeatCount', '3');
          anim.setAttribute('fill', 'freeze');
          anim.beginElement();
        }
        var leftArm = legs[0];
        var laAnim = leftArm.querySelector('animateTransform');
        if (laAnim) {
          laAnim.setAttribute('values', '0,55,150;-15,55,150;0,55,150');
          laAnim.setAttribute('dur', '0.25s');
          laAnim.setAttribute('repeatCount', '3');
          laAnim.setAttribute('fill', 'freeze');
          laAnim.beginElement();
        }
      }
      setTimeout(function () { resumeDefault(svg); }, 1500);
    },
    antenna: function (svg) {
      isRobotBusy = true;
      var bounce = svg.querySelector('animateTransform');
      if (bounce) bounce.setAttribute('repeatCount', '0');
      svg.style.transition = 'transform 0.08s ease';
      var count = 0;
      var bob = setInterval(function () {
        if (count >= 8) {
          clearInterval(bob);
          svg.style.transform = 'translateX(24px) translateY(18px)';
          svg.style.transition = '';
          resumeDefault(svg);
          return;
        }
        svg.style.transform = 'translateX(24px) translateY(' + (count % 2 ? 12 : 24) + 'px)';
        count++;
      }, 80);
    },
    miss: function (svg) {
      isRobotBusy = true;
      svg.style.transition = 'filter 0.1s ease, opacity 0.1s ease';
      svg.style.filter = 'brightness(2) drop-shadow(0 0 20px rgba(0,229,232,0.15))';
      setTimeout(function () {
        svg.style.filter = '';
        svg.style.transition = '';
        resumeDefault(svg);
      }, 400);
    }
  };

  // ===== Anger Mode =====
  function enterAngerMode() {
    if (isAnger) return;
    isAnger = true;
    document.body.classList.add('anger');

    // Robot shake
    robotSvg.style.animation = 'anger-shake 0.08s linear infinite';

    // Stop all default anims
    robotSvg.querySelectorAll('animate, animateTransform').forEach(function (a) { a.setAttribute('repeatCount', '0'); a.setAttribute('fill', 'freeze'); });

    // Add warning bars and text
    var layer = document.querySelector('.stream-layer');
    if (layer) {
      for (var i = 0; i < 10; i++) {
        var wb = document.createElement('div');
        wb.className = 'warning-bar active';
        wb.style.top = (15 + Math.random() * 70) + '%';
        wb.style.left = (Math.random() * 10) + '%';
        wb.style.width = (15 + Math.random() * 60) + '%';
        wb.style.animation = (i % 2 ? 'flowR ' : 'flowL ') + (8 + Math.random() * 10) + 's linear infinite';
        layer.appendChild(wb);
      }
    }

    // Warning floating text
    var warnTexts = ['WARNING', 'SYSTEM OVERRIDE', 'ANOMALY DETECTED', 'UNAUTHORIZED ACCESS'];
    var bodyEl = document.body;
    warnTexts.forEach(function (t) {
      var el = document.createElement('div');
      el.className = 'warning-float active';
      el.textContent = t;
      el.style.top = (10 + Math.random() * 75) + '%';
      el.style.left = (5 + Math.random() * 60) + '%';
      el.style.fontSize = (9 + Math.random() * 6) + 'px';
      el.style.animation = (Math.random() > 0.5 ? 'flowR ' : 'flowL ') + (12 + Math.random() * 10) + 's linear infinite';
      bodyEl.appendChild(el);
    });

    // Update status bar
    var topTags = document.querySelectorAll('.top-section .sys-tag');
    if (topTags.length >= 2) {
      topTags[0].innerHTML = '<span class="dot-on" style="background:#FF2D2D;box-shadow:0 0 8px rgba(255,45,45,0.6)"></span> <span style="color:#FF2D2D">⚠ SYSTEM ALERT ⚠</span>';
      topTags[1].innerHTML = '<span class="dot-on" style="background:#FF2D2D;box-shadow:0 0 8px rgba(255,45,45,0.6)"></span> <span style="color:#FF2D2D">ANOMALY DETECTED</span>';
    }

    // Recover after 10s
    setTimeout(function () {
      // Restore top tags
      if (topTags.length >= 2) {
        topTags[0].innerHTML = '<span class="dot-on"></span> WECHAT-BOT <span class="hl">OS</span>';
        topTags[1].innerHTML = '<span class="dot-on"></span> CONNECTION <span class="hl">ACTIVE</span>';
      }

      // Remove anger class (transition will smooth colors)
      document.body.classList.remove('anger');

      // Remove warning elements
      document.querySelectorAll('.warning-bar, .warning-float').forEach(function (el) { el.remove(); });

      // Restore robot
      robotSvg.style.animation = '';
      resetAnims(robotSvg);

      isAnger = false;
    }, 10000);
  }

  function checkAnger() {
    var now = Date.now();
    if (now - lastClickTime > 5000) {
      angerClickCount = 0;
    }
    lastClickTime = now;
    angerClickCount++;
    if (angerClickCount >= 8 && !isAnger) {
      angerClickCount = 0;
      enterAngerMode();
    }
  }

  // ===== Robot Click Handler =====
  robotSvg.addEventListener('click', function (e) {
    if (isRobotBusy || isAnger) return;
    var part = getHitPart(robotSvg, e.clientX, e.clientY);
    if (actions[part]) actions[part](robotSvg);
    checkAnger();
  });

  // ===== Terminal Click Handler =====
  termEl.addEventListener('click', function () {
    var pool = logPool.slice();
    shuffle(pool);
    var selected = pool.slice(0, 6);
    runTerm(selected);
  });

  // ===== Hex display =====
  function initHex() {
    if (!hexEl) return;
    var hex = '0123456789ABCDEF', h = '';
    for (var i = 0; i < 40; i++) h += hex[Math.floor(Math.random() * 16)] + '<br>';
    hexEl.innerHTML = h;
    setInterval(function () {
      var arr = h.split('<br>');
      arr.shift();
      arr.push(hex[Math.floor(Math.random() * 16)]);
      h = arr.join('<br>');
      hexEl.innerHTML = h;
    }, 1200);
  }

  // ===== Init =====
  document.addEventListener('DOMContentLoaded', function () {
    runTerm(defaultLogs);
    startCD();
    initHex();
    saveOriginalAnims();
    if (!('ontouchstart' in window)) initGridTrail();
  });
})();
