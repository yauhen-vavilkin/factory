(function () {
  // The server prints every absolute time in UTC (PageTime); this rewrites it to the browser's local time in the
  // same format. The title keeps the UTC time. A relative time ("5 min ago") keeps its text and gets the local
  // time as its title.
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function pad(value) {
    return value < 10 ? '0' + value : String(value);
  }

  function formatTime(date, format, utc) {
    var year = utc ? date.getUTCFullYear() : date.getFullYear();
    var month = utc ? date.getUTCMonth() : date.getMonth();
    var day = utc ? date.getUTCDate() : date.getDate();
    var clock = pad(utc ? date.getUTCHours() : date.getHours()) + ':'
      + pad(utc ? date.getUTCMinutes() : date.getMinutes());
    var seconds = ':' + pad(utc ? date.getUTCSeconds() : date.getSeconds());
    if (format === 'clock') return clock + seconds;
    if (format === 'day') return MONTHS[month] + ' ' + day + ', ' + clock;
    return year + '-' + pad(month + 1) + '-' + pad(day) + ' ' + clock + seconds;
  }
  window.formatTime = formatTime;

  function localizeTimes(root) {
    root.querySelectorAll('time[data-local][datetime]').forEach(function (element) {
      var date = new Date(element.getAttribute('datetime'));
      if (isNaN(date.getTime())) return;
      var format = element.getAttribute('data-local');
      if (format === 'ago') {
        element.title = formatTime(date, 'timestamp', false);
        return;
      }
      element.title = formatTime(date, 'timestamp', true) + ' UTC';
      element.textContent = formatTime(date, format, false);
    });
  }
  window.localizeTimes = localizeTimes;

  document.addEventListener('DOMContentLoaded', function () { localizeTimes(document); });
})();
