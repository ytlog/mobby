(function () {
  'use strict';

  var STORAGE_KEY = 'weekend-bag.v1';
  var CIRCUMFERENCE = 2 * Math.PI * 52;

  var CHECKLIST = [
    {
      id: 'essentials',
      name: 'Essentials',
      icon: '\u2708\uFE0F',
      items: [
        { id: 'phone', label: 'Phone + charger' },
        { id: 'wallet', label: 'Wallet & ID' },
        { id: 'keys', label: 'Keys' },
        { id: 'tickets', label: 'Tickets / reservations', note: 'Train, bus or hotel confirmation' },
        { id: 'cash', label: 'Some cash' }
      ]
    },
    {
      id: 'clothing',
      name: 'Clothing',
      icon: '\uD83D\uDC55',
      items: [
        { id: 'outfits', label: '2 outfits' },
        { id: 'undies', label: 'Underwear & socks' },
        { id: 'sleepwear', label: 'Sleepwear' },
        { id: 'jacket', label: 'Light jacket', note: 'Evenings get cold' },
        { id: 'shoes', label: 'Extra shoes' }
      ]
    },
    {
      id: 'toiletries',
      name: 'Toiletries',
      icon: '\uD83E\uDDF4',
      items: [
        { id: 'toothbrush', label: 'Toothbrush & paste' },
        { id: 'deodorant', label: 'Deodorant' },
        { id: 'skincare', label: 'Face wash & moisturiser' },
        { id: 'meds', label: 'Medication' },
        { id: 'hairbrush', label: 'Hairbrush' }
      ]
    },
    {
      id: 'tech',
      name: 'Tech',
      icon: '\uD83D\uDD0C',
      items: [
        { id: 'powerbank', label: 'Power bank' },
        { id: 'cables', label: 'Charging cables' },
        { id: 'headphones', label: 'Headphones' },
        { id: 'adapter', label: 'Travel adapter' }
      ]
    },
    {
      id: 'extras',
      name: 'Nice to have',
      icon: '\u2728',
      items: [
        { id: 'book', label: 'Book or e-reader' },
        { id: 'snacks', label: 'Snacks for the trip' },
        { id: 'waterbottle', label: 'Water bottle' },
        { id: 'sunglasses', label: 'Sunglasses' },
        { id: 'umbrella', label: 'Compact umbrella' }
      ]
    }
  ];

  var TOTAL = CHECKLIST.reduce(function (sum, group) {
    return sum + group.items.length;
  }, 0);

  var dom = {
    groups: document.getElementById('groups'),
    ringBar: document.getElementById('ringBar'),
    ringValue: document.getElementById('ringValue'),
    ringCount: document.getElementById('ringCount'),
    progressTitle: document.getElementById('progressTitle'),
    progressSub: document.getElementById('progressSub'),
    subtitle: document.getElementById('subtitle'),
    resetBtn: document.getElementById('resetBtn')
  };

  var inputsById = {};
  var state = loadState();

  function loadState() {
    var fallback = {};
    try {
      var raw = window.localStorage.getItem(STORAGE_KEY);
      if (!raw) return fallback;
      var parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object') return fallback;
      var clean = {};
      CHECKLIST.forEach(function (group) {
        group.items.forEach(function (item) {
          if (parsed[item.id] === true) clean[item.id] = true;
        });
      });
      return clean;
    } catch (err) {
      return fallback;
    }
  }

  function saveState() {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (err) {
      /* storage unavailable (private mode) - app still works this session */
    }
  }

  function buildGroups() {
    CHECKLIST.forEach(function (group) {
      var section = document.createElement('section');
      section.className = 'group';
      section.setAttribute('aria-labelledby', 'group-' + group.id);

      var head = document.createElement('div');
      head.className = 'group-head';

      var icon = document.createElement('span');
      icon.className = 'group-icon';
      icon.setAttribute('aria-hidden', 'true');
      icon.textContent = group.icon;

      var name = document.createElement('h2');
      name.className = 'group-name';
      name.id = 'group-' + group.id;
      name.textContent = group.name;

      var counter = document.createElement('span');
      counter.className = 'group-progress';
      counter.dataset.groupCount = group.id;

      head.appendChild(icon);
      head.appendChild(name);
      head.appendChild(counter);

      var list = document.createElement('ul');
      list.className = 'items';

      group.items.forEach(function (item) {
        var li = document.createElement('li');

        var label = document.createElement('label');
        label.className = 'item';
        label.setAttribute('for', 'cb-' + item.id);

        var checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.id = 'cb-' + item.id;
        checkbox.checked = state[item.id] === true;
        checkbox.dataset.itemId = item.id;
        checkbox.addEventListener('change', onToggle);

        var text = document.createElement('span');
        text.className = 'item-text';
        text.textContent = item.label;

        if (item.note) {
          var note = document.createElement('span');
          note.className = 'item-note';
          note.textContent = item.note;
          text.appendChild(note);
        }

        label.appendChild(checkbox);
        label.appendChild(text);
        li.appendChild(label);
        list.appendChild(li);

        inputsById[item.id] = { input: checkbox, row: label, group: group.id };
      });

      section.appendChild(head);
      section.appendChild(list);
      dom.groups.appendChild(section);
    });
  }

  function onToggle(event) {
    var id = event.target.dataset.itemId;
    if (event.target.checked) {
      state[id] = true;
    } else {
      delete state[id];
    }
    saveState();
    render();
  }

  function doneCount() {
    return Object.keys(state).length;
  }

  function render() {
    var done = doneCount();
    var ratio = TOTAL === 0 ? 0 : done / TOTAL;
    var percent = Math.round(ratio * 100);

    dom.ringBar.style.strokeDasharray = CIRCUMFERENCE.toFixed(2);
    dom.ringBar.style.strokeDashoffset = (CIRCUMFERENCE * (1 - ratio)).toFixed(2);
    dom.ringValue.textContent = percent + '%';
    dom.ringCount.textContent = done + ' / ' + TOTAL;

    Object.keys(inputsById).forEach(function (id) {
      var entry = inputsById[id];
      entry.row.classList.toggle('done', entry.input.checked);
    });

    CHECKLIST.forEach(function (group) {
      var counter = dom.groups.querySelector('[data-group-count="' + group.id + '"]');
      if (!counter) return;
      var groupDone = group.items.filter(function (item) {
        return state[item.id] === true;
      }).length;
      counter.textContent = groupDone + '/' + group.items.length;
    });

    if (done === 0) {
      dom.progressTitle.textContent = "Let's get packing";
      dom.progressSub.textContent = 'Tick items off as they go in the bag.';
      dom.subtitle.textContent = 'Nothing checked yet';
    } else if (done === TOTAL) {
      dom.progressTitle.textContent = 'Bag is ready';
      dom.progressSub.textContent = 'Everything on the list is packed. Have a great trip!';
      dom.subtitle.textContent = 'All ' + TOTAL + ' items packed';
    } else {
      var left = TOTAL - done;
      dom.progressTitle.textContent = left + (left === 1 ? ' item to go' : ' items to go');
      dom.progressSub.textContent = 'You have packed ' + done + ' of ' + TOTAL + '.';
      dom.subtitle.textContent = done + ' of ' + TOTAL + ' packed';
    }

    dom.resetBtn.hidden = done === 0;
  }

  dom.resetBtn.addEventListener('click', function () {
    state = {};
    Object.keys(inputsById).forEach(function (id) {
      inputsById[id].input.checked = false;
    });
    saveState();
    render();
  });

  window.addEventListener('storage', function (event) {
    if (event.key !== STORAGE_KEY) return;
    state = loadState();
    Object.keys(inputsById).forEach(function (id) {
      inputsById[id].input.checked = state[id] === true;
    });
    render();
  });

  buildGroups();
  render();
})();
