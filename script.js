const form = document.querySelector("#record-form");
const typeInput = document.querySelector("#type");
const descriptionInput = document.querySelector("#description");
const amountInput = document.querySelector("#amount");
const balanceText = document.querySelector("#balance");
const recordList = document.querySelector("#record-list");
const clearButton = document.querySelector("#clear-button");

let records = JSON.parse(localStorage.getItem("moneyRecords")) || [];

function saveRecords() {
  localStorage.setItem("moneyRecords", JSON.stringify(records));
}

function formatMoney(amount) {
  return `${amount.toFixed(2)} 元`;
}

function updatePage() {
  recordList.innerHTML = "";

  if (records.length === 0) {
    recordList.innerHTML = '<li class="empty">还没有记录。</li>';
  }

  let balance = 0;

  records.forEach((record) => {
    const item = document.createElement("li");
    const amount = record.type === "income" ? record.amount : -record.amount;

    balance += amount;
    item.className = `record-item ${record.type}`;
    item.innerHTML = `
      <span>${record.description}</span>
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

updatePage();
