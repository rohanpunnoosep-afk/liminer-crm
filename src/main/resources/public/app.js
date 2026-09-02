const SESSION_KEY = "liminer.token";
const POLL_INTERVAL_MS = 2000;
const MAX_HISTORY = 5;

const $ = (id) => document.getElementById(id);

let token = null;
let workflows = [];
let activeJobId = null;
let activePollHandle = null;
let activeJobStartedMs = null;
let durationTickHandle = null;
const history = [];

function saveToken(value) {
  token = value;
  if (value) {
    sessionStorage.setItem(SESSION_KEY, value);
  } else {
    sessionStorage.removeItem(SESSION_KEY);
  }
}

function loadToken() {
  token = sessionStorage.getItem(SESSION_KEY);
  return token;
}

async function apiFetch(path, options = {}) {
  const headers = Object.assign({}, options.headers || {});
  if (token) {
    headers["Authorization"] = "Bearer " + token;
  }

  const response = await fetch(path, Object.assign({}, options, { headers }));

  if (response.status === 401) {
    saveToken(null);
    showLoginView();
    throw new Error("session expired");
  }

  return response;
}

function showToast(message) {
  const toast = $("toast");
  toast.textContent = message;
  toast.hidden = false;
  setTimeout(() => {
    toast.hidden = true;
  }, 3000);
}

function showLoginView() {
  $("loginView").hidden = false;
  $("dashboardView").hidden = true;
  $("onboardView").hidden = true;
  $("documentsView").hidden = true;
  $("sessionBox").hidden = true;
  stopPolling();
}

function showDashboardView(email) {
  $("loginView").hidden = true;
  $("dashboardView").hidden = false;
  $("onboardView").hidden = true;
  $("documentsView").hidden = true;
  $("sessionBox").hidden = false;
  $("sessionEmail").textContent = email || "";
}

function showOnboardView() {
  $("loginView").hidden = true;
  $("dashboardView").hidden = true;
  $("onboardView").hidden = false;
  $("documentsView").hidden = true;
  $("sessionBox").hidden = true;
  stopPolling();
  resetOnboardWizard();
  obGoToStep(1);
}

function showDocumentsView() {
  $("loginView").hidden = true;
  $("dashboardView").hidden = true;
  $("onboardView").hidden = true;
  $("documentsView").hidden = false;
  $("sessionBox").hidden = false;
  closeBriefDetail();
  loadBriefs();
}

async function handleLogin(event) {
  event.preventDefault();

  const email = $("loginEmail").value.trim();
  const errorBox = $("loginError");
  errorBox.hidden = true;

  if (!email) {
    return;
  }

  const btn = $("btnLogin");
  btn.disabled = true;

  try {
    const response = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    });

    const data = await response.json();

    if (!response.ok) {
      errorBox.textContent = data.error || "Login failed.";
      errorBox.hidden = false;
      return;
    }

    saveToken(data.token);
    showDashboardView(data.email);
    await loadWorkflows();
  } catch (e) {
    errorBox.textContent = "Could not reach the server.";
    errorBox.hidden = false;
  } finally {
    btn.disabled = false;
  }
}

function handleLogout() {
  saveToken(null);
  workflows = [];
  activeJobId = null;
  stopPolling();
  showLoginView();
}

async function loadWorkflows() {
  try {
    const response = await apiFetch("/api/workflows");
    const data = await response.json();
    workflows = data.workflows || [];
    renderWorkflows();
  } catch (e) {
    showToast("Could not load workflows.");
  }
}

function renderWorkflows() {
  const grid = $("workflow-grid");
  grid.innerHTML = "";

  workflows.forEach((wf) => {
    const card = document.createElement("div");
    card.className = "workflow-card" + (wf.available ? "" : " is-disabled");
    card.dataset.workflowId = wf.id;

    const name = document.createElement("h3");
    name.className = "workflow-name";
    name.textContent = wf.name;

    const desc = document.createElement("p");
    desc.className = "workflow-desc";
    desc.textContent = wf.available ? wf.description : (wf.reason || wf.description);

    const pill = document.createElement("span");
    pill.className = "status-pill";
    pill.setAttribute("aria-live", "polite");
    pill.hidden = true;

    const runBtn = document.createElement("button");
    runBtn.type = "button";
    runBtn.className = "btn btn-run";
    runBtn.textContent = "Run";
    runBtn.disabled = !wf.available || activeJobId !== null;
    runBtn.addEventListener("click", () => runWorkflow(wf));

    card.appendChild(name);
    card.appendChild(desc);
    card.appendChild(pill);
    card.appendChild(runBtn);
    grid.appendChild(card);
  });
}

function setRunButtonsDisabled(disabled) {
  document.querySelectorAll(".workflow-card .btn-run").forEach((btn) => {
    const card = btn.closest(".workflow-card");
    const wf = workflows.find((w) => w.id === card.dataset.workflowId);
    btn.disabled = disabled || !wf || !wf.available;
  });
}

function cardPill(workflowId) {
  const card = document.querySelector(`.workflow-card[data-workflow-id="${workflowId}"]`);
  return card ? card.querySelector(".status-pill") : null;
}

const STATUS_LABELS = { QUEUED: "Queued", RUNNING: "Running", DONE: "Done", NOOP: "No-op", FAILED: "Failed" };

function setCardStatus(workflowId, status, summary) {
  const pill = cardPill(workflowId);
  if (!pill) return;

  pill.hidden = false;
  pill.className = "status-pill " + status.toLowerCase();
  pill.title = summary || "";
  pill.textContent = "";

  if (status === "RUNNING") {
    const spinner = document.createElement("span");
    spinner.className = "spinner";
    pill.appendChild(spinner);
  }

  pill.appendChild(document.createTextNode(STATUS_LABELS[status] || status));
}

let pendingPlanWorkflow = null;

async function runWorkflow(wf) {
  if (activeJobId !== null) {
    return;
  }

  if (wf.hasPlan) {
    await showWorkflowPlan(wf);
    return;
  }

  await executeWorkflowRun(wf);
}

async function showWorkflowPlan(wf) {
  const card = document.querySelector(`.workflow-card[data-workflow-id="${wf.id}"]`);
  const runBtn = card ? card.querySelector(".btn-run") : null;
  if (runBtn) runBtn.disabled = true;

  try {
    const response = await apiFetch(`/api/workflows/${encodeURIComponent(wf.id)}/plan`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });

    const data = await response.json();

    if (!response.ok) {
      showToast(data.error || "Could not preview this workflow.");
      return;
    }

    pendingPlanWorkflow = wf;
    renderWorkflowPlanPanel(wf, data);
  } catch (e) {
    showToast("Could not preview this workflow.");
  } finally {
    if (runBtn) runBtn.disabled = !wf.available || activeJobId !== null;
  }
}

function renderWorkflowPlanPanel(wf, plan) {
  $("planWorkflowName").textContent = wf.name;

  const summaryEl = $("planSummary");
  const listEl = $("planColumns");
  const confirmBtn = $("btnPlanConfirm");
  listEl.innerHTML = "";

  if (plan.blockingError) {
    summaryEl.textContent = plan.blockingError;
    confirmBtn.disabled = true;
  } else {
    const rowCount = plan.eligibleRowCount || 0;
    summaryEl.textContent = `This will update ${rowCount} row${rowCount === 1 ? "" : "s"}.`;
    confirmBtn.disabled = false;

    const columns = plan.columnsToWrite || [];
    if (columns.length === 0) {
      const li = document.createElement("li");
      li.className = "empty-note";
      li.textContent = "No columns will be written.";
      listEl.appendChild(li);
    } else {
      columns.forEach((col) => {
        const li = document.createElement("li");
        li.textContent = col;
        listEl.appendChild(li);
      });
    }
  }

  $("workflowPlanPanel").hidden = false;
}

function closeWorkflowPlanPanel() {
  $("workflowPlanPanel").hidden = true;
  pendingPlanWorkflow = null;
}

async function confirmWorkflowPlan() {
  const wf = pendingPlanWorkflow;
  closeWorkflowPlanPanel();
  if (wf) {
    await executeWorkflowRun(wf);
  }
}

async function executeWorkflowRun(wf) {
  if (activeJobId !== null) {
    return;
  }

  try {
    const response = await apiFetch(`/api/workflows/${encodeURIComponent(wf.id)}/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });

    const data = await response.json();

    if (response.status === 409) {
      showToast("another job is running");
      return;
    }

    if (!response.ok) {
      showToast(data.error || "Could not start workflow.");
      return;
    }

    activeJobId = data.jobId;
    activeJobStartedMs = Date.now();
    setRunButtonsDisabled(true);
    setCardStatus(wf.id, "QUEUED");
    openOutputPanel(wf);
    startPolling(wf);
  } catch (e) {
    showToast("Could not start workflow.");
  }
}

function openOutputPanel(wf) {
  $("outputWorkflowName").textContent = wf.name;
  $("outputStatusPill").className = "status-pill queued";
  $("outputStatusPill").textContent = "Queued";
  $("outputDuration").textContent = "";
  $("outputSummary").textContent = "";
  $("outputPre").textContent = "";
  setOutputCollapsed(false);
  startDurationTick();
}

function startDurationTick() {
  stopDurationTick();
  durationTickHandle = setInterval(updateDurationLabel, 500);
  updateDurationLabel();
}

function stopDurationTick() {
  if (durationTickHandle) {
    clearInterval(durationTickHandle);
    durationTickHandle = null;
  }
}

function updateDurationLabel() {
  if (!activeJobStartedMs) return;
  const seconds = Math.round((Date.now() - activeJobStartedMs) / 1000);
  $("outputDuration").textContent = seconds + "s";
}

function startPolling(wf) {
  stopPolling();
  activePollHandle = setInterval(() => pollJob(wf), POLL_INTERVAL_MS);
  pollJob(wf);
}

function stopPolling() {
  if (activePollHandle) {
    clearInterval(activePollHandle);
    activePollHandle = null;
  }
  stopDurationTick();
}

async function pollJob(wf) {
  if (!activeJobId) return;

  try {
    const response = await apiFetch(`/api/jobs/${encodeURIComponent(activeJobId)}`);
    const job = await response.json();

    setCardStatus(wf.id, job.status, job.summary);

    const pre = $("outputPre");
    pre.textContent = job.output || "";
    pre.scrollTop = pre.scrollHeight;

    const statusPill = $("outputStatusPill");
    statusPill.className = "status-pill " + job.status.toLowerCase();
    statusPill.textContent = STATUS_LABELS[job.status] || job.status;

    const summaryEl = $("outputSummary");
    summaryEl.textContent = (job.status === "NOOP" || job.status === "FAILED") ? (job.summary || "") : "";

    $("outputCost").textContent = formatCost(job.cost);

    if (job.status === "DONE" || job.status === "NOOP" || job.status === "FAILED") {
      stopPolling();
      finishJob(wf, job);
    }
  } catch (e) {
    // apiFetch already handles 401; other errors just wait for next tick
  }
}

function formatCost(cost) {
  if (!cost) return "";
  const usd = typeof cost.usd === "number" ? cost.usd : 0;
  const calls = typeof cost.calls === "number" ? cost.calls : 0;
  return `${calls} call${calls === 1 ? "" : "s"} · $${usd.toFixed(4)}`;
}

function finishJob(wf, job) {
  activeJobId = null;
  activeJobStartedMs = null;
  setRunButtonsDisabled(false);

  const durationMs = job.startedAt && job.finishedAt
    ? new Date(job.finishedAt).getTime() - new Date(job.startedAt).getTime()
    : null;
  const durationLabel = durationMs !== null ? Math.round(durationMs / 1000) + "s" : "";
  $("outputDuration").textContent = durationLabel;

  history.unshift({
    workflowName: wf.name,
    status: job.status,
    summary: job.summary,
    duration: durationLabel,
    finishedAt: job.finishedAt,
    cost: job.cost,
  });
  history.length = Math.min(history.length, MAX_HISTORY);
  renderHistory();
}

function renderHistory() {
  const list = $("historyList");
  list.innerHTML = "";

  history.forEach((entry) => {
    const li = document.createElement("li");

    const costLabel = formatCost(entry.cost);
    const label = document.createElement("span");
    label.textContent = entry.workflowName
      + (entry.duration ? ` (${entry.duration})` : "")
      + (costLabel ? ` — ${costLabel}` : "");

    const pill = document.createElement("span");
    pill.className = "status-pill " + entry.status.toLowerCase();
    pill.title = entry.summary || "";
    pill.textContent = STATUS_LABELS[entry.status] || entry.status;

    li.appendChild(label);
    li.appendChild(pill);

    if (entry.summary && (entry.status === "NOOP" || entry.status === "FAILED")) {
      const summaryEl = document.createElement("span");
      summaryEl.className = "history-summary";
      summaryEl.textContent = entry.summary;
      li.appendChild(summaryEl);
    }
    list.appendChild(li);
  });
}

function setOutputCollapsed(collapsed) {
  $("outputBody").classList.toggle("collapsed", collapsed);
  $("btnToggleOutput").setAttribute("aria-expanded", String(!collapsed));
  $("outputChevron").textContent = collapsed ? "▶" : "▼";
}

function toggleOutputPanel() {
  const collapsed = $("outputBody").classList.contains("collapsed");
  setOutputCollapsed(!collapsed);
}

async function tryResumeSession() {
  const saved = loadToken();
  if (!saved) {
    showLoginView();
    return;
  }

  try {
    const response = await apiFetch("/api/session");
    const data = await response.json();
    showDashboardView(data.email);
    await loadWorkflows();
  } catch (e) {
    showLoginView();
  }
}

// ---- Onboarding wizard ----

let obInput = {};
let obDraftId = null;
let obTabs = [];
let obFields = { mainFields: [], intakeFields: [] };
let obSchema = null;
let obPlanValid = false;

function resetOnboardWizard() {
  obInput = {};
  obDraftId = null;
  obTabs = [];
  obFields = { mainFields: [], intakeFields: [] };
  obSchema = null;
  obPlanValid = false;
  $("obError").hidden = true;
}

function obShowError(message) {
  const box = $("obError");
  if (Array.isArray(message)) {
    box.textContent = message.join(" ");
  } else {
    box.textContent = message;
  }
  box.hidden = false;
}

function obClearError() {
  $("obError").hidden = true;
}

function obGoToStep(step) {
  for (let i = 1; i <= 4; i++) {
    $("onboardStep" + i).hidden = i !== step;
  }
  document.querySelectorAll("#stepIndicator li").forEach((li) => {
    li.classList.toggle("active", Number(li.dataset.step) === step);
  });
  obClearError();
}

function obPipe(id) {
  return $(id).value.trim();
}

function handleObNext1() {
  obClearError();

  const email = $("obEmail").value.trim();
  const fundName = $("obFundName").value.trim();

  const errors = [];
  if (!email || !email.includes("@")) {
    errors.push("A valid email is required.");
  }
  if (!fundName) {
    errors.push("Fund name is required.");
  }

  if (errors.length) {
    obShowError(errors);
    return;
  }

  obInput = {
    email,
    fundName,
    internalFundName: obPipe("obInternalFundName"),
    internalWebsite: obPipe("obInternalWebsite"),
    internalNames: obPipe("obInternalNames"),
    internalEmails: obPipe("obInternalEmails"),
    clientSectorTags: obPipe("obSectorTags"),
    clientMicrosectorTags: obPipe("obMicrosectorTags"),
    clientGeography: obPipe("obGeography"),
    clientStages: obPipe("obStages"),
    clientInvestmentThesis: obPipe("obThesis"),
  };

  obGoToStep(2);
}

async function handleObDetect() {
  obClearError();

  const spreadsheetId = $("obSpreadsheetId").value.trim();
  const possibleTabNames = $("obTabNames").value.trim();

  if (!spreadsheetId || !possibleTabNames) {
    obShowError("Spreadsheet ID and possible tab names are required.");
    return;
  }

  const body = Object.assign({}, obInput, { spreadsheetId, possibleTabNames });

  const btn = $("btnObDetect");
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "Detecting schema…";

  try {
    const response = await fetch("/api/onboard/detect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    const data = await response.json();

    if (response.status === 400) {
      obShowError(data.errors || ["Please check your inputs."]);
      return;
    }

    if (response.status === 502) {
      obShowError(data.error || "Could not detect schema.");
      return;
    }

    if (!response.ok) {
      obShowError(data.error || "Could not detect schema.");
      return;
    }

    obInput = body;
    obDraftId = data.draftId;
    obTabs = data.tabs || [];
    obFields = data.fields || { mainFields: [], intakeFields: [] };
    obSchema = data.schema || { mainTabName: "", intakeTabName: "", mainTabMappings: {}, intakeTabMappings: {} };
    if (!obSchema.mainTabMappings) obSchema.mainTabMappings = {};
    if (!obSchema.intakeTabMappings) obSchema.intakeTabMappings = {};

    obPlanValid = false;
    obGoToStep(3);
    renderObReviewStep();
  } catch (e) {
    obShowError("Could not reach the server.");
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

function obHeadersForTab(tabName) {
  let tab = obTabs.find((t) => t.tabName === tabName);
  if (!tab) {
    const needle = (tabName || "").trim().toLowerCase();
    tab = obTabs.find((t) => (t.tabName || "").trim().toLowerCase() === needle);
  }
  return tab ? tab.headers || [] : [];
}

function renderObReviewStep() {
  const tabsBox = $("obReviewTabs");
  tabsBox.innerHTML = "";

  tabsBox.appendChild(obBuildTabSelect("mainTabName", "Main CRM tab"));
  tabsBox.appendChild(obBuildTabSelect("intakeTabName", "Email intake tab"));

  renderObReviewGrid();
}

function obBuildTabSelect(schemaKey, labelText) {
  const wrap = document.createElement("div");
  wrap.className = "form-field";

  const label = document.createElement("label");
  label.textContent = labelText;
  label.setAttribute("for", "obTabSelect_" + schemaKey);

  const select = document.createElement("select");
  select.id = "obTabSelect_" + schemaKey;

  obTabs.forEach((tab) => {
    const option = document.createElement("option");
    option.value = tab.tabName;
    option.textContent = tab.tabName;
    if (tab.tabName === obSchema[schemaKey]) {
      option.selected = true;
    }
    select.appendChild(option);
  });

  select.addEventListener("change", () => {
    obSchema[schemaKey] = select.value;
    obPlanValid = false;
    renderObReviewGrid();
  });

  wrap.appendChild(label);
  wrap.appendChild(select);
  return wrap;
}

function renderObReviewGrid() {
  const grid = $("obReviewGrid");
  grid.innerHTML = "";

  const warnings = [];
  obWarnIfTabUnresolved(warnings, "Main CRM tab", obSchema.mainTabName);
  obWarnIfTabUnresolved(warnings, "Email intake tab", obSchema.intakeTabName);
  if (warnings.length > 0) {
    obShowError(warnings);
  } else {
    obClearError();
  }

  grid.appendChild(obBuildFieldGroup("Main tab fields", obFields.mainFields, "mainTabMappings", obSchema.mainTabName));
  grid.appendChild(obBuildFieldGroup("Intake tab fields", obFields.intakeFields, "intakeTabMappings", obSchema.intakeTabName));
}

function obWarnIfTabUnresolved(warnings, labelText, tabName) {
  if (!tabName || obHeadersForTab(tabName).length > 0) {
    return;
  }
  const scannedNames = obTabs.map((t) => t.tabName).join(", ");
  warnings.push(
    labelText + ' "' + tabName + '" does not match any scanned tab (' + scannedNames +
    "). Please pick the correct tab from the dropdown above."
  );
}

function obBuildFieldGroup(title, fields, mappingsKey, tabName) {
  const container = document.createElement("div");

  const heading = document.createElement("div");
  heading.className = "review-row-group-title";
  heading.textContent = title;
  container.appendChild(heading);

  const headers = obHeadersForTab(tabName);
  const mappings = obSchema[mappingsKey];

  (fields || []).forEach((field) => {
    const row = document.createElement("div");
    row.className = "review-row";

    const label = document.createElement("span");
    label.className = "review-row-label";
    label.textContent = field.displayName;
    row.appendChild(label);

    const select = document.createElement("select");

    const noneOption = document.createElement("option");
    noneOption.value = "";
    noneOption.textContent = "— not mapped —";
    select.appendChild(noneOption);

    const currentValue = mappings[field.key] || "";
    let matched = false;

    headers.forEach((header) => {
      const option = document.createElement("option");
      option.value = header;
      option.textContent = header;
      if (header === currentValue) {
        option.selected = true;
        matched = true;
      }
      select.appendChild(option);
    });

    if (!matched && currentValue) {
      const orphanOption = document.createElement("option");
      orphanOption.value = currentValue;
      orphanOption.textContent = currentValue;
      orphanOption.selected = true;
      select.appendChild(orphanOption);
      matched = true;
    } else if (!matched) {
      mappings[field.key] = "";
    }

    const pill = document.createElement("span");
    pill.className = "status-pill unmapped";
    pill.textContent = "unmapped";
    pill.hidden = matched;

    select.addEventListener("change", () => {
      mappings[field.key] = select.value;
      pill.hidden = select.value !== "";
      obPlanValid = false;
    });

    row.appendChild(select);
    row.appendChild(pill);
    container.appendChild(row);
  });

  return container;
}

async function handleObPreview() {
  obClearError();

  const btn = $("btnObPreview");
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "Checking your sheet…";

  try {
    const response = await fetch("/api/onboard/preview", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ draftId: obDraftId, schema: obSchema }),
    });

    const data = await response.json();

    if (response.status === 404) {
      obShowError("This onboarding draft expired — please detect the schema again.");
      obGoToStep(2);
      return;
    }

    if (response.status === 400) {
      obShowError(data.errors || ["Please check your mapping."]);
      return;
    }

    if (response.status === 502) {
      obShowError(data.error || "Could not preview changes.");
      return;
    }

    if (!response.ok) {
      obShowError(data.error || "Could not preview changes.");
      return;
    }

    obPlanValid = true;
    renderObPreviewStep(data);
    obGoToStep(4);
  } catch (e) {
    obShowError("Could not reach the server.");
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

function renderObPreviewStep(plan) {
  const mainCount = (plan.mainToAdd || []).length;
  const intakeCount = (plan.intakeToAdd || []).length;

  $("obPreviewSummary").textContent =
    `Liminer will add ${mainCount} new column${mainCount === 1 ? "" : "s"} to ${obSchema.mainTabName} ` +
    `and ${intakeCount} to ${obSchema.intakeTabName}. No existing columns or data will be changed.`;

  obRenderPreviewList("obPreviewMain", plan.mainToAdd, "this tab");
  obRenderPreviewList("obPreviewIntake", plan.intakeToAdd, "this tab");

  const note = $("obDividerNote");
  note.classList.remove("insert");

  if (plan.dividerAction === "present") {
    note.textContent = "The || Liminer || divider column is already in place.";
  } else if (plan.dividerAction === "append") {
    note.textContent = "A divider column will be added to separate your columns from Liminer's generated ones.";
  } else if (plan.dividerAction === "insert") {
    note.textContent = "A divider column will be INSERTED, shifting some existing columns to the right.";
    note.classList.add("insert");
  } else {
    note.textContent = "";
  }
}

function obRenderPreviewList(listId, items, scopeLabel) {
  const list = $(listId);
  list.innerHTML = "";

  if (!items || items.length === 0) {
    const li = document.createElement("li");
    li.className = "empty-note";
    li.textContent = `Nothing to add — ${scopeLabel} already has every column Liminer needs.`;
    list.appendChild(li);
    return;
  }

  items.forEach((header) => {
    const li = document.createElement("li");
    li.textContent = header;
    list.appendChild(li);
  });
}

async function handleObConfirm() {
  obClearError();

  if (!obPlanValid) {
    obShowError("Please preview your changes before confirming.");
    return;
  }

  const btn = $("btnObConfirm");
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = "Creating account…";

  try {
    const response = await fetch("/api/onboard/confirm", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ draftId: obDraftId, schema: obSchema }),
    });

    const data = await response.json();

    if (response.status === 404) {
      obShowError("This onboarding draft expired — please detect the schema again.");
      obGoToStep(2);
      return;
    }

    if (!response.ok) {
      obShowError(data.error || (data.errors && data.errors.join(" ")) || "Could not create account.");
      return;
    }

    saveToken(data.token);
    showDashboardView(data.email);
    await loadWorkflows();
  } catch (e) {
    obShowError("Could not reach the server.");
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

function handleResetConfirmInput() {
  const input = $("resetConfirmEmail");
  const btn = $("btnReset");
  const currentEmail = $("sessionEmail").textContent;
  btn.disabled = input.value.trim() !== currentEmail;
}

async function handleResetAccount() {
  const input = $("resetConfirmEmail");
  const btn = $("btnReset");
  const errorBox = $("resetError");
  const successBox = $("resetSuccess");
  const currentEmail = $("sessionEmail").textContent;

  errorBox.hidden = true;
  successBox.hidden = true;

  if (input.value.trim() !== currentEmail) {
    errorBox.textContent = "Email does not match.";
    errorBox.hidden = false;
    return;
  }

  btn.disabled = true;
  const originalText = btn.textContent;
  btn.textContent = "Resetting…";

  try {
    const response = await apiFetch("/api/user/reset", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ confirm: currentEmail }),
    });

    const data = await response.json();

    if (!response.ok) {
      errorBox.textContent = data.error || "Reset failed.";
      errorBox.hidden = false;
      return;
    }

    successBox.textContent = "Account reset. Redirecting to login…";
    successBox.hidden = false;
    setTimeout(() => {
      handleLogout();
    }, 1500);
  } catch (e) {
    errorBox.textContent = "Could not reach the server.";
    errorBox.hidden = false;
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

// ---- Documents (investor briefs) ----

let briefs = [];

async function loadBriefs() {
  const listEl = $("briefsList");
  const emptyEl = $("briefsEmpty");
  listEl.innerHTML = "";

  try {
    const response = await apiFetch("/api/briefs");
    const data = await response.json();
    briefs = data.briefs || [];
  } catch (e) {
    showToast("Could not load documents.");
    briefs = [];
  }

  if (briefs.length === 0) {
    emptyEl.hidden = false;
    return;
  }

  emptyEl.hidden = true;
  renderBriefsList();
}

function renderBriefsList() {
  const listEl = $("briefsList");
  listEl.innerHTML = "";

  briefs.forEach((brief) => {
    const row = document.createElement("div");
    row.className = "brief-row";

    const info = document.createElement("div");
    info.className = "brief-row-info";

    const name = document.createElement("span");
    name.className = "brief-row-name";
    name.textContent = brief.contactName || "(no name)";

    const meta = document.createElement("span");
    meta.className = "brief-row-meta";
    meta.textContent = [brief.fundName, formatBriefDate(brief.asOfDate)].filter(Boolean).join(" — ");

    info.appendChild(name);
    info.appendChild(meta);

    const openBtn = document.createElement("button");
    openBtn.type = "button";
    openBtn.className = "btn btn-ghost-light";
    openBtn.textContent = "View";
    openBtn.addEventListener("click", () => openBriefDetail(brief.rowNumber));

    row.appendChild(info);
    row.appendChild(openBtn);
    listEl.appendChild(row);
  });
}

function formatBriefDate(asOfDate) {
  if (!asOfDate) return "";
  const idx = asOfDate.indexOf("T");
  return idx > 0 ? asOfDate.substring(0, idx) : asOfDate;
}

let activeBriefRow = null;

async function openBriefDetail(row) {
  try {
    const response = await apiFetch(`/api/briefs/${encodeURIComponent(row)}`);

    if (!response.ok) {
      showToast("Could not load this document.");
      return;
    }

    const brief = await response.json();
    activeBriefRow = row;
    renderBriefDetail(brief);
  } catch (e) {
    showToast("Could not load this document.");
  }
}

function closeBriefDetail() {
  activeBriefRow = null;
  $("briefDetailPanel").hidden = true;
}

function renderBriefDetail(brief) {
  const contact = brief.contactAndFirmProfile || {};
  const market = brief.marketIntelligence || {};
  const relationship = brief.relationshipSummary || {};
  const callPrep = brief.callPreparation || {};

  const contactName = [contact.firstName, contact.lastName].filter(Boolean).join(" ").trim();
  $("briefDetailTitle").textContent = contactName || "Investor Brief";
  $("briefDetailMeta").textContent = [contact.fundName, formatBriefDate(brief.asOfDate)].filter(Boolean).join(" — ");

  setBriefSection("briefSectionExecutiveSummary", !!brief.executiveSummary, () => {
    $("briefExecutiveSummaryText").textContent = brief.executiveSummary || "";
  });

  setBriefSection("briefSectionContact", Object.keys(contact).length > 0, () => {
    renderBriefFields("briefContactFields", [
      ["Email", contact.email],
      ["Website", contact.website],
      ["Investor Type", contact.typeOfInvestor],
      ["Sectors", joinBriefArray(contact.sectorTags)],
      ["Microsectors", joinBriefArray(contact.microsectorTags)],
      ["Geography", joinBriefArray(contact.geography)],
      ["Prior Backed Funds", joinBriefArray(contact.priorBackedFunds)],
      ["Investment Thesis", contact.investmentThesis],
    ]);
  });

  setBriefSection("briefSectionMarket", Object.keys(market).length > 0, () => {
    renderBriefFields("briefMarketFields", [
      ["Funding Status", market.fundingStatus],
      ["Resources Score", market.resourcesScore],
      ["Fit Score", market.fitScore],
      ["Probability Now", market.probabilityNow],
      ["Identity Status", market.identityStatus],
      ["CRD #", market.crdNumber],
      ["CIK #", market.cikNumber],
      ["LEI", market.lei],
      ["EIN", market.ein],
    ]);
  });

  setBriefSection("briefSectionRelationship", Object.keys(relationship).length > 0, () => {
    renderBriefFields("briefRelationshipFields", [
      ["Analysis Date", formatBriefDate(relationship.analysisDate)],
      ["Interests", joinBriefArray(relationship.aggregatedInterests)],
      ["Sentiment Over Time", relationship.sentimentChangesOverTime],
      ["Narrative Arc", relationship.narrativeArc],
      ["Outstanding Commitments", joinBriefArray(relationship.outstandingCommitments)],
    ]);
  });

  setBriefSection("briefSectionCallPrep", Object.keys(callPrep).length > 0, () => {
    renderCallPrep(callPrep);
  });

  $("briefDetailPanel").hidden = false;
}

function setBriefSection(sectionId, hasContent, renderFn) {
  const section = $(sectionId);
  if (!hasContent) {
    section.hidden = true;
    return;
  }
  renderFn();
  section.hidden = false;
}

function renderBriefFields(containerId, pairs) {
  const dl = $(containerId);
  dl.innerHTML = "";

  pairs.forEach(([label, value]) => {
    if (value === undefined || value === null || value === "") return;
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = String(value);
    dl.appendChild(dt);
    dl.appendChild(dd);
  });
}

function joinBriefArray(arr) {
  return Array.isArray(arr) ? arr.filter(Boolean).join(", ") : "";
}

function renderCallPrep(callPrep) {
  const container = $("briefCallPrepBlocks");
  container.innerHTML = "";

  addCallPrepList(container, "Talking Points", callPrep.talkingPoints);
  addCallPrepList(container, "Suggested Questions", callPrep.suggestedQuestions);
  addCallPrepList(container, "Relationship-Building Opportunities", callPrep.relationshipBuildingOpportunities);
  addCallPrepList(container, "Recommended Next Steps", callPrep.recommendedNextSteps);

  const objections = Array.isArray(callPrep.anticipatedObjections) ? callPrep.anticipatedObjections : [];
  if (objections.length > 0) {
    const heading = document.createElement("h4");
    heading.textContent = "Anticipated Objections";
    container.appendChild(heading);

    const ul = document.createElement("ul");
    objections.forEach((o) => {
      const li = document.createElement("li");
      if (o && typeof o === "object") {
        const objection = o.objection || "";
        const navigation = o.navigation || "";
        li.textContent = navigation ? `${objection} → ${navigation}` : objection;
      } else {
        li.textContent = String(o);
      }
      ul.appendChild(li);
    });
    container.appendChild(ul);
  }
}

function addCallPrepList(container, title, items) {
  if (!Array.isArray(items) || items.length === 0) return;

  const heading = document.createElement("h4");
  heading.textContent = title;
  container.appendChild(heading);

  const ul = document.createElement("ul");
  items.forEach((item) => {
    const li = document.createElement("li");
    li.textContent = String(item);
    ul.appendChild(li);
  });
  container.appendChild(ul);
}

async function downloadActiveBriefPdf() {
  if (activeBriefRow === null) return;

  try {
    const response = await apiFetch(`/api/briefs/${encodeURIComponent(activeBriefRow)}/pdf`);

    if (!response.ok) {
      showToast("Could not download this document.");
      return;
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "investor-brief.pdf";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  } catch (e) {
    showToast("Could not download this document.");
  }
}

function init() {
  $("loginForm").addEventListener("submit", handleLogin);
  $("btnLogout").addEventListener("click", handleLogout);
  $("btnToggleOutput").addEventListener("click", toggleOutputPanel);

  $("btnShowOnboard").addEventListener("click", (e) => {
    e.preventDefault();
    showOnboardView();
  });
  $("btnShowLogin").addEventListener("click", showLoginView);

  $("btnObNext1").addEventListener("click", handleObNext1);
  $("btnObBack2").addEventListener("click", () => obGoToStep(1));
  $("btnObDetect").addEventListener("click", handleObDetect);
  $("btnObBack3").addEventListener("click", () => obGoToStep(2));
  $("btnObPreview").addEventListener("click", handleObPreview);
  $("btnObBack4").addEventListener("click", () => {
    obPlanValid = false;
    obGoToStep(3);
  });
  $("btnObConfirm").addEventListener("click", handleObConfirm);

  $("btnPlanCancel").addEventListener("click", closeWorkflowPlanPanel);
  $("btnPlanConfirm").addEventListener("click", confirmWorkflowPlan);

  $("resetConfirmEmail").addEventListener("input", handleResetConfirmInput);
  $("btnReset").addEventListener("click", handleResetAccount);

  $("btnShowDocuments").addEventListener("click", showDocumentsView);
  $("btnDocumentsBack").addEventListener("click", () => showDashboardView($("sessionEmail").textContent));
  $("btnDownloadBriefPdf").addEventListener("click", downloadActiveBriefPdf);

  tryResumeSession();
}

init();
