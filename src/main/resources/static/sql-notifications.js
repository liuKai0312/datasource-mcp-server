// System notifications only bring the user back to the approval page; they never approve SQL.
class SqlApprovalNotifications {
    constructor(button, status, onOpen, onEnabled) {
        this.button = button;
        this.status = status;
        this.onOpen = onOpen;
        this.onEnabled = onEnabled;
        this.storageKey = 'dameng_mcp_sql_notifications';
        this.enabled = false;
        try { this.enabled = localStorage.getItem(this.storageKey) === 'true'; } catch (_) { /* session-only */ }
        this.seen = new Set();
        this.notifications = new Map();
        this.failure = '';
        button.addEventListener('click', () => this.toggle());
        this.render();
    }

    supported() { return window.isSecureContext && 'Notification' in window; }

    render() {
        this.button.disabled = !this.supported();
        this.button.textContent = this.enabled && this.supported() && Notification.permission === 'granted'
            ? '关闭浏览器通知' : '开启浏览器通知';
        if (!window.isSecureContext) {
            this.status.textContent = '浏览器通知需要 HTTPS 或 localhost；内网 IP 的普通 HTTP 地址无法授权。页面确认弹窗仍可使用。';
        } else if (!('Notification' in window)) {
            this.status.textContent = '当前浏览器不支持系统通知，请使用页面确认弹窗。';
        } else if (Notification.permission === 'denied') {
            this.status.textContent = '通知权限已被浏览器阻止，请在此站点的浏览器设置中允许通知后重试。';
        } else if (this.failure) {
            this.status.textContent = this.failure;
        } else {
            this.status.textContent = this.enabled && Notification.permission === 'granted'
                ? '浏览器通知已开启。请保持页面打开；后台休眠、系统勿扰或通知设置可能导致提醒延迟或不显示。'
                : '点击开启并允许通知后，新的待确认 SQL 将发送系统通知。';
        }
    }

    async toggle() {
        if (!this.supported()) { this.render(); return; }
        this.button.disabled = true;
        try {
            if (this.enabled && Notification.permission === 'granted') {
                this.enabled = false;
                this.notifications.forEach(notification => notification.close());
                this.notifications.clear();
                this.seen.clear();
            } else {
                // Permission must be requested from a deliberate button click, never from polling.
                const permission = Notification.permission === 'default'
                    ? await Notification.requestPermission() : Notification.permission;
                this.enabled = permission === 'granted';
                if (permission === 'default') this.failure = '尚未授权通知，可再次点击开启。';
            }
            try { localStorage.setItem(this.storageKey, String(this.enabled)); } catch (_) { /* session-only */ }
            if (this.enabled) { this.failure = ''; await this.onEnabled(); }
        } catch (_) {
            this.failure = '通知授权失败，请检查浏览器的站点通知设置。页面确认弹窗仍可使用。';
        } finally { this.render(); }
    }

    sync(pending) {
        const active = new Set(pending.map(request => request.id));
        for (const [id, notification] of this.notifications) {
            if (!active.has(id)) { notification.close(); this.notifications.delete(id); }
        }
        for (const id of this.seen) if (!active.has(id)) this.seen.delete(id);
        this.render();
        if (!this.enabled || !this.supported() || Notification.permission !== 'granted' || this.failure) return;
        for (const request of pending) {
            if (this.seen.has(request.id) || Date.parse(request.expiresAt) <= Date.now()) continue;
            try {
                const notification = new Notification('SQL 执行等待确认', {
                    body: '有数据库修改请求等待处理，点击返回管理页核对 SQL 并批准或拒绝。',
                    tag: 'sql-approval-' + request.id,
                    // Avoid displaying SQL or business data on the desktop or lock screen.
                    renotify: false
                });
                this.seen.add(request.id);
                this.notifications.set(request.id, notification);
                notification.onclick = () => { notification.close(); this.onOpen(request.id); };
                notification.onerror = () => {
                    this.failure = '浏览器未能显示系统通知，请检查浏览器及系统通知设置。页面确认弹窗仍可使用。';
                    this.render();
                };
            } catch (_) {
                this.failure = '当前浏览器无法发送系统通知，请使用页面确认弹窗，或改用桌面浏览器。';
                this.render();
                break;
            }
        }
    }

    close() {
        this.notifications.forEach(notification => notification.close());
        this.notifications.clear();
    }
}
