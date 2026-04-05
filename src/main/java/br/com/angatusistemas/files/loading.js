(function () {
    if (window.loading) return;

    const loaderHTML = `
    <div id="global-loading"
         class="fixed inset-0 z-[9999] hidden items-center justify-center
                bg-black/80 backdrop-blur-sm
                transition-opacity duration-500">

      <div class="relative flex flex-col items-center gap-6">

        <!-- Glow monocromático -->
        <div class="absolute w-40 h-40 rounded-full
                    bg-white/10
                    blur-3xl opacity-70 animate-pulse">
        </div>

        <!-- Spinner preto e branco -->
        <div class="relative w-16 h-16 rounded-full
                    border-[4px] border-white/20
                    border-t-white animate-spin">
        </div>

        <!-- Texto -->
        <span class="relative text-white/80 text-sm tracking-[0.3em] uppercase animate-pulse">
          Carregando
        </span>

      </div>
    </div>
  `;

    function create() {
        if (!document.getElementById("global-loading")) {
            document.body.insertAdjacentHTML("beforeend", loaderHTML);
        }
    }

    function show() {
        create();
        const el = document.getElementById("global-loading");
        el.classList.remove("hidden");
        el.classList.add("flex", "opacity-100");
    }

    function hide() {
        const el = document.getElementById("global-loading");
        if (!el) return;

        el.classList.add("opacity-0");
        setTimeout(() => {
            el.classList.add("hidden");
            el.classList.remove("flex", "opacity-0");
        }, 400);
    }

    window.loading = { show, hide };
})();
