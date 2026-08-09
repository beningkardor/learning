const form = document.querySelector("#record-form");
const typeInput = document.querySelector("#type");
const categoryInput = document.querySelector("#category");
const descriptionInput = document.querySelector("#description");
const amountInput = document.querySelector("#amount");
const balanceText = document.querySelector("#balance");
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

function updatePage() {
  recordList.innerHTML = "";

  const selectedCategory = filterCategory.value;
  const visibleRecords = records.filter((record) => {
    return selectedCategory === "all" || record.category === selectedCategory;
  });

  if (visibleRecords.length === 0) {
    recordList.innerHTML = '<li class="empty">还没有记录。</li>';
  }

  let balance = 0;

  records.forEach((record) => {
    const amount = record.type === "income" ? record.amount : -record.amount;
    balance += amount;
  });

  visibleRecords.forEach((record) => {
    const item = document.createElement("li");

    item.className = `record-item ${record.type}`;
    item.innerHTML = `
      <span class="record-info">
        <span>${record.description}</span>
        <span class="category-tag">${record.category || "未分类"}</span>
      </span>
      <strong>${record.type === "income" ? "+" : "-"}${formatMoney(record.amount)}</strong>
    `;

    recordList.appendChild(item);
  });

  balanceText.textContent = formatMoney(balance);
}

form.addEventListener("submit", (event) => {
  event.preventDefault();

  const newRecord = {
    type: typeInput.value,
    category: categoryInput.value,
    description: descriptionInput.value.trim(),
    amount: Number(amountInput.value),
  };

  if (!newRecord.description || newRecord.amount <= 0) {
    return;
  }

  records.push(newRecord);
  saveRecords();
  updatePage();
  form.reset();
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

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("service-worker.js");
}

Array.from(categoryInput.options).forEach((option) => {
  const filterOption = document.createElement("option");

  filterOption.value = option.value;
  filterOption.textContent = option.textContent;
  filterCategory.appendChild(filterOption);
});

updatePage();
