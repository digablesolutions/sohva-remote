// Sohva Remote landing page. Everything here is an enhancement: the page reads fine without it.
(function () {
  'use strict';
  // Reveal styles only apply once this script runs, so the page never hides content without it.
  document.documentElement.classList.add('js');
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var hasIO = 'IntersectionObserver' in window;

  // Header gets a hairline once the page scrolls.
  var head = document.querySelector('.site-head');
  if (head) {
    var onScroll = function () { head.classList.toggle('is-stuck', window.scrollY > 8); };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  // Evening guide: mark the slot that is on now, or the next one, by the viewer's clock.
  // Every slot runs Monday to Friday; 06.15 is the earliest event of a day.
  (function () {
    var slots = [].slice.call(document.querySelectorAll('.slot[data-start]'));
    var status = document.getElementById('guide-status');
    if (!slots.length) return;
    var min = function (s) { var p = s.split(':'); return p[0] * 60 + +p[1]; };
    var weekday = function (n) { return n >= 1 && n <= 5; };
    var d = new Date(), day = d.getDay(), now = d.getHours() * 60 + d.getMinutes();
    var byTime = slots.slice().sort(function (a, b) { return min(a.dataset.start) - min(b.dataset.start); });
    var mark = function (slot, kind, label) {
      var pill = document.createElement('span');
      pill.className = 'pill ' + kind;
      pill.textContent = label;
      slot.querySelector('.what').appendChild(pill);
      slot.classList.add('is-live');
    };
    if (weekday(day)) {
      var current = slots.filter(function (s) {
        return s.dataset.end && now >= min(s.dataset.start) && now < min(s.dataset.end);
      })[0];
      if (current) { mark(current, 'now', 'On now'); return; }
    } else if (status) {
      status.textContent = day === 6
        ? "It's the weekend, so the scenes are resting until Monday."
        : "It's Sunday, so the weekday starts again tomorrow at 06.15.";
      status.hidden = false;
    }
    // Find the next event, looking ahead up to a week.
    for (var offset = 0; offset < 8; offset++) {
      var dd = (day + offset) % 7;
      if (!weekday(dd)) continue;
      var next = byTime.filter(function (s) { return offset > 0 || min(s.dataset.start) > now; })[0];
      if (next) {
        mark(next, 'next', offset === 0 ? 'Next' : offset === 1 ? 'Tomorrow' : 'Monday');
        return;
      }
    }
  })();

  // Evening guide: the sticky phone shows Timers when the night and morning rows are in view.
  (function () {
    var phone = document.querySelector('.guide-phone');
    var caption = document.getElementById('guide-caption');
    if (!phone || !hasIO) return;
    var timerRows = [].slice.call(document.querySelectorAll('.slot[data-screen="timers"]'));
    var visible = new Set();
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) visible.add(e.target); else visible.delete(e.target); });
      var timers = visible.size > 0;
      phone.dataset.screen = timers ? 'timers' : 'scenes';
      if (caption) caption.textContent = timers ? 'Sleep timer and wake-up in the Timers tab' : 'The same evening in the Scenes tab';
    }, { rootMargin: '-40% 0px -40% 0px' });
    timerRows.forEach(function (r) { io.observe(r); });
  })();

  // Gentle reveal for a few blocks. CSS keeps them visible unless this runs.
  (function () {
    var items = [].slice.call(document.querySelectorAll('.reveal'));
    if (reduce || !hasIO) { items.forEach(function (el) { el.classList.add('is-in'); }); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        // Also release anything already scrolled past (e.g. after jumping to an anchor).
        if (e.isIntersecting || e.boundingClientRect.top < 0) { e.target.classList.add('is-in'); io.unobserve(e.target); }
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    items.forEach(function (el) { io.observe(el); });
  })();

  // Screenshot strip: previous / next buttons when it overflows.
  (function () {
    var strip = document.getElementById('strip');
    var nav = document.querySelector('.gallery-nav');
    if (!strip || !nav) return;
    var buttons = [].slice.call(nav.querySelectorAll('button'));
    var update = function () {
      var max = strip.scrollWidth - strip.clientWidth;
      nav.hidden = max < 8;
      buttons[0].disabled = strip.scrollLeft <= 4;
      buttons[1].disabled = strip.scrollLeft >= max - 4;
    };
    buttons.forEach(function (b) {
      b.addEventListener('click', function () {
        var step = strip.querySelector('li').getBoundingClientRect().width + 24;
        strip.scrollBy({ left: step * +b.dataset.dir, behavior: reduce ? 'auto' : 'smooth' });
      });
    });
    strip.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    update();
  })();
})();
