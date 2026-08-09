const form = document.querySelector("#record-form");
const typeInput = document.querySelector("#type");
const categoryInput = document.querySelector("#category");
const recordDateInput = document.querySelector("#record-date");
const descriptionInput = document.querySelector("#description");
const amountInput = document.querySelector("#amount");
const monthFilter = document.querySelector("#month-filter");
const expenseTotalText = document.querySelector("#expense-total");
const categorySummary = document.querySelector("#category-summary");
const recordList = document.querySelector("#record-list");
const clearButton = document.querySelector("#clear-button");
const quickExpenseButtons = document.querySelectorAll(".quick-expense");
const filterCategory = document.querySelector("#filter-category");

let records = JSON.parse(localStorage.getItem("moneyRecords")) || [];

function saveRecords() {
  localStorage.setItem("moneyRecords", JSON.stringify(records));
}

function formatMoney(amount) {
  return `${amount.toFixed(2)} 元`;
}

function getToday() {
  return new Date().toISOString().slice(0, 10);
}

function getCurrentMonth() {
  return getToday().slice(0, 7);
}

function updatePage() {
  recordList.innerHTML = "";
  categorySummary.innerHTML = "";

  const selectedMonth = monthFilter.value;
  const selectedCategory = filterCategory.value;
  const monthlyRecords = records.filter((record) => {
    const recordDate = record.date || getToday();

    return recordDate.startsWith(selectedMonth);
  });
  const visibleRecords = monthlyRecords.filter((record) => {
    return selectedCategory === "all" || record.category === selectedCategory;
  });

  if (visibleRecords.length === 0) {
    recordList.innerHTML = '<li class="empty">还没有记录。</li>';
  }

  let expenseTotal = 0;
  const categoryTotals = {};

  monthlyRecords.forEach((record) => {
    if (record.type !== "expense") {
      return;
    }

    const category = record.category || "未分类";

    expenseTotal += record.amount;
    categoryTotals[category] = (categoryTotals[category] || 0) + record.amount;
  });

  visibleRecords.forEach((record) => {
    const item = document.createElement("li");

    item.className = `record-item ${record.type}`;
    item.innerHTML = `
      <span class="record-info">
        <span>${record.description}</span>
        <span class="category-tag">${record.category || "未分类"}</span>
        <span class="record-date">${record.date || "未记录日期"}</span>
      </span>
      <strong>${record.type === "income" ? "+" : "-"}${formatMoney(record.amount)}</strong>
    `;

    recordList.appendChild(item);
  });

  Object.keys(categoryTotals).forEach((category) => {
    const item = document.createElement("li");

    item.className = "category-summary-item";
    item.innerHTML = `
      <span>${category}</span>
      <strong>${formatMoney(categoryTotals[category])}</strong>
    `;

    categorySummary.appendChild(item);
  });

  if (Object.keys(categoryTotals).length === 0) {
    categorySummary.innerHTML = '<li class="empty">这个月还没有支出。</li>';
  }

  expenseTotalText.textContent = formatMoney(expenseTotal);
}

form.addEventListener("submit", (event) => {
  event.preventDefault();

  const newRecord = {
    type: typeInput.value,
    category: categoryInput.value,
    date: recordDateInput.value,
    description: descriptionInput.value.trim(),
    amount: Number(amountInput.value),
  };

  if (!newRecord.description || !newRecord.date || newRecord.amount <= 0) {
    return;
  }

  records.push(newRecord);
  saveRecords();
  updatePage();
  form.reset();
  recordDateInput.value = getToday();
});

clearButton.addEventListener("click", () => {
  records = [];
  saveRecords();
  updatePage();
});

quickExpenseButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const category = button.dataset.category;

    typeInput.value = "expense";
    categoryInput.value = category;
    descriptionInput.value = category;
    amountInput.focus();
  });
});

filterCategory.addEventListener("change", updatePage);
monthFilter.addEventListener("change", updatePage);

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("service-worker.js");
}

Array.from(categoryInput.options).forEach((option) => {
  const filterOption = document.createElement("option");

  filterOption.value = option.value;
  filterOption.textContent = option.textContent;
  filterCategory.appendChild(filterOption);
});

monthFilter.value = getCurrentMonth();
recordDateInput.value = getToday();
updatePage();
