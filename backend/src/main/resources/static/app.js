const loginForm = document.querySelector('#login');
const lookupForm = document.querySelector('#lookup');
const receiptForm = document.querySelector('#receipt');
const shipmentStatus = document.querySelector('#shipment');
const workspace = document.querySelector('#workspace');
const signIn = document.querySelector('#sign-in');
const home = document.querySelector('#home');
const intake = document.querySelector('#intake');
const navigation = document.querySelector('#navigation');
const account = document.querySelector('#account');
const menu = document.querySelector('#menu');
const footer = document.querySelector('#footer');
const cellSelect = receiptForm.elements.cellId;

const roleNames = {
  EMPLOYEE: 'Сотрудник ПВЗ',
  SUPERVISOR: 'Супервайзер региона',
  ANALYST: 'Операционный аналитик',
};

const statuses = {
  EXPECTED: 'Ожидается', STORED: 'На хранении', ISSUED: 'Выдано',
  RETURN_STORED: 'Возврат на хранении', RETURN_SHIPPED: 'Возврат отгружен',
};
const operationNames = {
  RECEIPT: 'Приём', ISSUE: 'Выдача', CUSTOMER_RETURN: 'Возврат', RETURN_DISPATCH: 'Отгрузка возврата',
};
const operationDate = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'Europe/Moscow',
});

let currentUser;
let currentShipment;
let cellsById = new Map();

function showMessage(text, error = false) {
  const message = document.querySelector(currentUser ? '#message' : '#login-message');
  message.dataset.error = error;
  message.textContent = text;
  message.hidden = !text;
}

function resetReceipt() {
  currentShipment = undefined;
  receiptForm.hidden = true;
  shipmentStatus.textContent = '';
}

function showLogin() {
  currentUser = undefined;
  resetReceipt();
  lookupForm.reset();
  workspace.hidden = true;
  signIn.hidden = false;
  footer.hidden = account.hidden = menu.hidden = true;
  account.open = false;
  setMenu(false);
}

async function api(path, options) {
  const response = await fetch('/api' + path, options);
  if (response.status === 204) return;
  const data = await response.json();
  if (response.ok) return data;
  if (response.status === 401) showLogin();
  throw new Error(data.detail || 'Запрос не выполнен.');
}

function post(path, body, headers = {}) {
  return api(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
}

function setMenu(open) {
  menu.setAttribute('aria-expanded', String(open));
  navigation.dataset.open = String(open);
}

function showHome(section = 'home') {
  intake.hidden = true;
  home.hidden = false;
  setMenu(false);
  for (const link of navigation.querySelectorAll('a')) {
    if (link.hash === '#' + section) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
}

function tableRows(id, rows, width) {
  const body = document.querySelector(id);
  if (rows.length === 0) {
    const empty = document.createElement('td');
    empty.colSpan = width;
    empty.textContent = 'Записей пока нет';
    const row = document.createElement('tr');
    row.append(empty);
    body.replaceChildren(row);
    return;
  }

  body.replaceChildren(...rows.map(values => {
    const row = document.createElement('tr');
    for (const value of values) {
      const cell = document.createElement('td');
      cell.textContent = value;
      row.append(cell);
    }
    return row;
  }));
}

async function refreshDashboard(user) {
  const [overview, cells] = await Promise.all([api('/overview'), api('/cells')]);
  if (currentUser !== user) return;
  const { totals, operations, shipments } = overview;
  document.querySelector('#stored').textContent = totals.stored;
  document.querySelector('#expected').textContent = totals.expected;
  document.querySelector('#total').textContent = totals.shipments;
  document.querySelector('#free-cells').textContent = cells.filter(cell => cell.freeLiters > 0).length;
  tableRows('#recent-rows', operations.map(item => [
    item.id, operationNames[item.type] ?? item.type, item.code ?? '—', operationDate.format(new Date(item.time)),
  ]), 4);
  tableRows('#cell-rows', cells.map(cell => [cell.number, cell.freeLiters]), 2);
  tableRows('#shipment-rows', shipments.map(shipment => [
    shipment.code, statuses[shipment.status], shipment.volumeLiters,
  ]), 3);
}

async function showWorkspace(user) {
  const points = await api('/pvz');
  const employee = user.role === 'EMPLOYEE';
  currentUser = user;
  resetReceipt();
  showHome();
  showMessage('');
  document.querySelector('#name').textContent = user.name;
  document.querySelector('#role').textContent = roleNames[user.role];
  document.querySelector('#point-title').textContent = employee
    ? 'Моя точка: ' + (points[0]?.name ?? 'ПВЗ недоступен')
    : 'Доступные пункты выдачи';
  document.querySelector('#points').replaceChildren(...points.map(point => {
    const item = document.createElement('li');
    item.textContent = employee ? point.address : point.name + ' — ' + point.address;
    return item;
  }));
  for (const element of document.querySelectorAll('[data-employee]')) {
    element.hidden = !employee;
  }
  for (const id of ['#recent-rows', '#cell-rows', '#shipment-rows']) {
    document.querySelector(id).replaceChildren();
  }
  for (const value of document.querySelectorAll('.metrics dd')) value.textContent = '—';
  workspace.hidden = footer.hidden = account.hidden = menu.hidden = false;
  signIn.hidden = true;
  if (employee) await refreshDashboard(user);
}

menu.addEventListener('click', () => setMenu(menu.getAttribute('aria-expanded') !== 'true'));
for (const link of document.querySelectorAll('a[href^="#"]')) {
  link.addEventListener('click', event => {
    event.preventDefault();
    const section = link.hash.slice(1);
    showHome(section);
    const target = document.getElementById(section);
    target.focus({ preventScroll: true });
    target.scrollIntoView();
  });
}
document.querySelector('#new-receipt').addEventListener('click', () => {
  showMessage('');
  home.hidden = true;
  intake.hidden = false;
  lookupForm.elements.code.focus();
});
const passwordToggle = document.querySelector('#toggle-password');
passwordToggle.addEventListener('click', () => {
  const password = loginForm.elements.password;
  const visible = password.type === 'password';
  password.type = visible ? 'text' : 'password';
  passwordToggle.setAttribute('aria-pressed', String(visible));
  passwordToggle.setAttribute('aria-label', visible ? 'Скрыть пароль' : 'Показать пароль');
});

function onSubmit(form, submit) {
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type=submit]');
    button.disabled = true;
    showMessage('');
    try {
      await submit();
    } catch (error) {
      showMessage(error.message, true);
    } finally {
      button.disabled = false;
    }
  });
}

onSubmit(loginForm, async () => {
  const credentials = Object.fromEntries(new FormData(loginForm));
  try {
    await showWorkspace(await post('/auth/login', credentials));
  } finally {
    loginForm.elements.password.value = '';
  }
});

document.querySelector('#logout').addEventListener('click', async () => {
  try {
    await post('/auth/logout', {});
    showLogin();
    showMessage('');
  } catch (error) {
    showMessage(error.message, true);
  }
});

onSubmit(lookupForm, async () => {
  resetReceipt();
  const code = lookupForm.elements.code.value.trim();
  if (!code) throw new Error('Введите код отправления.');
  const [shipment, cells] = await Promise.all([
    api('/shipments/' + encodeURIComponent(code)),
    api('/cells'),
  ]);
  currentShipment = shipment;
  cellsById = new Map(cells.map(cell => [cell.id, cell]));
  const cell = cellsById.get(shipment.cellId);
  shipmentStatus.textContent = shipment.code + ' · ' + statuses[shipment.status]
    + ' · ' + shipment.volumeLiters + ' л' + (cell ? ' · ячейка №' + cell.number : '');

  const available = cells.filter(item => item.freeLiters >= shipment.volumeLiters);
  cellSelect.replaceChildren(...available.map(item =>
    new Option('№' + item.number + ' · свободно ' + item.freeLiters + ' л', item.id)));
  receiptForm.hidden = shipment.status !== 'EXPECTED' || available.length === 0;
  if (shipment.status === 'EXPECTED' && available.length === 0) {
    showMessage('Нет ячейки с достаточным свободным объёмом.', true);
  }
});

onSubmit(receiptForm, async () => {
  const shipment = currentShipment;
  const cellId = Number(cellSelect.value);
  const user = currentUser;
  const cellNumber = cellsById.get(cellId).number;
  const storageKey = 'pvz.receipt.' + JSON.stringify([user.id, shipment.code, cellId]);
  // Keep the key when the response is interrupted and the user retries.
  const key = sessionStorage.getItem(storageKey) || crypto.randomUUID();
  sessionStorage.setItem(storageKey, key);
  const lookupButton = lookupForm.querySelector('button');
  lookupButton.disabled = true;
  try {
    const result = await post('/receipts', { code: shipment.code, cellId }, { 'Idempotency-Key': key });
    sessionStorage.removeItem(storageKey);
    if (currentUser === user) {
      resetReceipt();
      lookupForm.reset();
      showHome();
      home.scrollIntoView();
      showMessage('Принято. Операция №' + result.operationId + ', ячейка №' + cellNumber
        + ', свободно ' + result.freeLiters + ' л.');
      try {
        await refreshDashboard(user);
      } catch {
        if (currentUser === user) showMessage('Приёмка сохранена. Обновите страницу, чтобы увидеть текущие данные.');
      }
    }
  } finally {
    lookupButton.disabled = false;
  }
});

try {
  await showWorkspace(await api('/auth/me'));
} catch (error) {
  if (currentUser) showMessage(error.message, true);
  else showLogin();
}
