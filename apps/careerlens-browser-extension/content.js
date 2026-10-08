(() => {
  if (document.getElementById('careerlens-boss-panel')) return

  const panel = document.createElement('aside')
  panel.id = 'careerlens-boss-panel'
  panel.innerHTML = `
    <button class="cl-toggle" type="button" aria-label="打开CareerLens面板">CL</button>
    <section class="cl-card" hidden>
      <header><strong>CareerLens</strong><button class="cl-close" type="button">×</button></header>
      <p class="cl-status"><i></i><span>正在检查本地服务…</span></p>
      <nav>
        <button data-path="/discovery">职位发现</button>
        <button data-path="/review-queue">投递审核</button>
        <button data-path="/automation">自动执行</button>
        <button data-path="/settings">平台设置</button>
      </nav>
      <small>所有发送仍需在CareerLens中审核，扩展不会直接投递。</small>
    </section>`
  document.documentElement.appendChild(panel)

  const card = panel.querySelector('.cl-card')
  const status = panel.querySelector('.cl-status')
  const setOpen = open => {
    card.hidden = !open
    chrome.storage.local.set({ careerLensPanelOpen: open })
  }
  panel.querySelector('.cl-toggle').addEventListener('click', () => setOpen(card.hidden))
  panel.querySelector('.cl-close').addEventListener('click', () => setOpen(false))
  panel.querySelectorAll('[data-path]').forEach(button => button.addEventListener('click', () => {
    window.open(`http://127.0.0.1:8888${button.dataset.path}`, 'careerlens-app')
  }))
  chrome.storage.local.get(['careerLensPanelOpen'], value => setOpen(Boolean(value.careerLensPanelOpen)))

  fetch('http://127.0.0.1:18080/api/v1/health')
    .then(response => {
      if (!response.ok) throw new Error()
      status.classList.add('cl-online')
      status.querySelector('span').textContent = '本地服务在线'
    })
    .catch(() => { status.querySelector('span').textContent = '本地服务未启动' })

})()
