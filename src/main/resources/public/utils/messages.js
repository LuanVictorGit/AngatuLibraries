// =============================================
// SISTEMA DE MENSAGENS (TOAST) - MODERN & RESPONSIVE
// =============================================
const messages = {
    container: null,

    init() {
        if (!document.getElementById("messages-container")) {
            // Alteração: Removi classes Tailwind excessivas para garantir controle total via CSS injetado
            const messagesHTML = `
                <div id="messages-container"></div>
            `;
            document.body.insertAdjacentHTML("afterbegin", messagesHTML);
        }

        this.container = document.getElementById("messages-container");
        this.injectStyles();
    },

    injectStyles() {
        if (!document.getElementById("messages-css")) {
            const style = document.createElement("style");
            style.id = "messages-css";
            style.textContent = this.getStyles();
            document.head.appendChild(style);
        }
    },

    getStyles() {
        return `
            /* ============================================= */
            /* SISTEMA DE MENSAGENS (TOAST) - MODERN & RESPONSIVE */
            /* MODIFICAÇÃO: Centralizado na tela - Mobile First */
            /* ============================================= */

            /* Container Principal - CENTRALIZADO */
            #messages-container {
                position: fixed;
                top: 20px;
                left: 50%;
                transform: translateX(-50%);
                z-index: 9999;
                display: flex;
                flex-direction: column;
                gap: 12px;
                pointer-events: none;
                width: 90%;
                max-width: 380px;
                align-items: center;
            }

            /* Responsividade (Mobile) */
            @media (max-width: 640px) {
                #messages-container {
                    top: 10px;
                    width: 95%;
                    max-width: 95%;
                }
            }

            /* Estilo do Card (Mensagem) */
            .message-item {
                position: relative;
                pointer-events: auto;
                width: 100%;
                background: rgba(20, 20, 25, 0.85); /* Fundo escuro translúcido */
                backdrop-filter: blur(12px); /* Efeito de desfoque moderno */
                -webkit-backdrop-filter: blur(12px);
                border: 1px solid rgba(255, 255, 255, 0.08);
                border-left: 4px solid transparent; /* Borda colorida à esquerda */
                border-radius: 12px;
                padding: 16px;
                box-shadow: 
                    0 10px 15px -3px rgba(0, 0, 0, 0.3), 
                    0 4px 6px -2px rgba(0, 0, 0, 0.1);
                color: #fff;
                font-family: 'Inter', system-ui, -apple-system, sans-serif;
                overflow: hidden;
                transform-origin: top center;
                animation: toastSlideIn 0.4s cubic-bezier(0.21, 1.02, 0.73, 1) forwards;
            }

            /* Animação de Saída */
            .message-item.hiding {
                animation: toastFadeOut 0.3s cubic-bezier(0.4, 0, 0.2, 1) forwards;
            }

            /* Barra de Progresso */
            .message-progress {
                position: absolute;
                bottom: 0;
                left: 0;
                height: 3px;
                background: linear-gradient(90deg, rgba(255,255,255,0.4), rgba(255,255,255,0.8));
                width: 100%;
                transform-origin: left;
                animation: progressBar linear forwards;
            }

            /* Layout Interno */
            .message-content {
                display: flex;
                align-items: flex-start;
                gap: 12px;
            }

            .message-icon-wrapper {
                display: flex;
                align-items: center;
                justify-content: center;
                flex-shrink: 0;
                width: 24px;
                height: 24px;
                margin-top: 2px;
            }
            
            .message-icon-svg {
                width: 100%;
                height: 100%;
            }

            .message-text {
                flex: 1;
            }

            .message-title {
                font-weight: 600;
                font-size: 0.95rem;
                margin-bottom: 4px;
                line-height: 1.2;
                letter-spacing: 0.01em;
            }

            .message-body {
                font-size: 0.85rem;
                color: rgba(255, 255, 255, 0.7);
                line-height: 1.4;
            }

            .close-btn {
                background: transparent;
                border: none;
                color: rgba(255, 255, 255, 0.4);
                cursor: pointer;
                padding: 4px;
                margin: -4px -4px 0 0;
                border-radius: 4px;
                transition: all 0.2s;
            }

            .close-btn:hover {
                color: white;
                background: rgba(255,255,255,0.1);
            }

            /* Keyframes */
            @keyframes toastSlideIn {
                0% { opacity: 0; transform: translateY(-20px) scale(0.95); }
                100% { opacity: 1; transform: translateY(0) scale(1); }
            }

            @keyframes toastFadeOut {
                0% { opacity: 1; transform: scale(1); }
                100% { opacity: 0; transform: scale(0.95); }
            }

            @keyframes progressBar {
                0% { transform: scaleX(1); }
                100% { transform: scaleX(0); }
            }

            /* Variantes de Cores (Borda lateral e Título) */
            .border-success { border-left-color: #10B981; }
            .text-success { color: #10B981; }
            .icon-success { color: #10B981; }

            .border-error { border-left-color: #EF4444; }
            .text-error { color: #EF4444; }
            .icon-error { color: #EF4444; }

            .border-info { border-left-color: #3B82F6; }
            .text-info { color: #3B82F6; }
            .icon-info { color: #3B82F6; }

            .border-warning { border-left-color: #F59E0B; }
            .text-warning { color: #F59E0B; }
            .icon-warning { color: #F59E0B; }
        `;
    },

    show(message, options = {}) {
        if (!this.container) this.init();

        const config = {
            type: options.type || "info",
            duration: options.duration || 4000, // Aumentei um pouco o tempo padrão para leitura
            title: options.title || this.getDefaultTitle(options.type || "info"),
            icon: options.icon || this.getDefaultIcon(options.type || "info"),
        };

        this.createMessage(message, config);
    },

    createMessage(message, config) {
        const messageId = "msg-" + Date.now();
        // Renderiza o SVG diretamente em vez de usar <img>
        const messageHTML = `
            <div id="${messageId}" class="message-item border-${config.type}">
                <div class="message-content">
                    <div class="message-icon-wrapper icon-${config.type}">
                        ${config.icon}
                    </div>
                    <div class="message-text">
                        ${config.title ? `<div class="message-title text-${config.type}">${config.title}</div>` : ""}
                        <div class="message-body">${message}</div>
                    </div>
                    <button class="close-btn" aria-label="Fechar">
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <line x1="18" y1="6" x2="6" y2="18"></line>
                            <line x1="6" y1="6" x2="18" y2="18"></line>
                        </svg>
                    </button>
                </div>
                <div class="message-progress" style="animation-duration: ${config.duration}ms"></div>
            </div>
        `;

        this.container.insertAdjacentHTML("afterbegin", messageHTML);
        const messageElement = document.getElementById(messageId);
        this.setupMessageEvents(messageElement, config.duration);
    },

    setupMessageEvents(element, duration) {
        const closeBtn = element.querySelector(".close-btn");

        closeBtn.addEventListener("click", () => this.removeMessage(element));

        // Pausa o timer se o mouse estiver em cima (UX Melhorada)
        let timeoutId;
        const startTimer = () => {
            timeoutId = setTimeout(() => this.removeMessage(element), duration);
            element.dataset.timeoutId = timeoutId;
        };

        // Inicia
        startTimer();

        // Limpeza simples ao terminar animação de saída
        element.addEventListener("animationend", (e) => {
            if (e.animationName === "toastFadeOut") element.remove();
        });
    },

    removeMessage(element) {
        if (!element) return;
        clearTimeout(element.dataset.timeoutId);
        element.classList.add("hiding");
    },

    // Helper para Títulos Padrão se o usuário não passar um
    getDefaultTitle(type) {
        const titles = {
            success: "Sucesso!",
            error: "Erro",
            info: "Informação",
            warning: "Atenção",
        };
        return titles[type] || "Notificação";
    },

    // Ícones SVG embutidos (Não precisa mais de imagens externas)
    getDefaultIcon(type) {
        const icons = {
            success: `<svg xmlns="http://www.w3.org/2000/svg" class="message-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path><polyline points="22 4 12 14.01 9 11.01"></polyline></svg>`,
            error: `<svg xmlns="http://www.w3.org/2000/svg" class="message-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>`,
            info: `<svg xmlns="http://www.w3.org/2000/svg" class="message-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`,
            warning: `<svg xmlns="http://www.w3.org/2000/svg" class="message-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>`,
        };
        return icons[type] || icons.info;
    },

    success(message, options = {}) {
        this.show(message, { ...options, type: "success" });
    },

    error(message, options = {}) {
        this.show(message, { ...options, type: "error" });
    },

    info(message, options = {}) {
        this.show(message, { ...options, type: "info" });
    },

    warning(message, options = {}) {
        this.show(message, { ...options, type: "warning" });
    },
};