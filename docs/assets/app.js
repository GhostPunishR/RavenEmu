document.documentElement.classList.add("js");

const menuStyles = document.createElement("link");
menuStyles.rel = "stylesheet";
menuStyles.href = "assets/mobile-menu.css";
document.head.appendChild(menuStyles);

const header = document.querySelector(".site-header");
const menuButton = document.querySelector(".menu-toggle");
const navigation = document.querySelector(".site-nav");
const navigationLinks = [...document.querySelectorAll('.site-nav a[href^="#"]')];
const filterButtons = [...document.querySelectorAll(".filter-button")];
const featureCards = [...document.querySelectorAll(".feature-card")];
const emptyState = document.querySelector("#filter-empty");
const copyButtons = [...document.querySelectorAll(".copy-button")];
const revealItems = [...document.querySelectorAll(".reveal")];
const sections = [...document.querySelectorAll("main section[id]")];

function setMenu(open) {
  if (!menuButton || !navigation) return;
  menuButton.setAttribute("aria-expanded", String(open));
  navigation.classList.toggle("is-open", open);
  document.body.classList.toggle("nav-open", open);
  const label = menuButton.querySelector(".sr-only");
  if (label) label.textContent = open ? "Fermer le menu" : "Ouvrir le menu";
}

menuButton?.addEventListener("click", () => {
  setMenu(menuButton.getAttribute("aria-expanded") !== "true");
});

navigationLinks.forEach((link) => {
  link.addEventListener("click", () => setMenu(false));
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") setMenu(false);
});

window.addEventListener("resize", () => {
  if (window.innerWidth > 860) setMenu(false);
});

function updateHeader() {
  header?.classList.toggle("is-scrolled", window.scrollY > 16);
}

updateHeader();
window.addEventListener("scroll", updateHeader, { passive: true });

filterButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const filter = button.dataset.filter || "all";
    let visibleCount = 0;

    filterButtons.forEach((item) => {
      const active = item === button;
      item.classList.toggle("is-active", active);
      item.setAttribute("aria-pressed", String(active));
    });

    featureCards.forEach((card) => {
      const visible = filter === "all" || card.dataset.category === filter;
      card.classList.toggle("is-hidden", !visible);
      card.setAttribute("aria-hidden", String(!visible));
      if (visible) visibleCount += 1;
    });

    if (emptyState) emptyState.hidden = visibleCount !== 0;
  });
});

async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const field = document.createElement("textarea");
  field.value = text;
  field.setAttribute("readonly", "");
  field.style.position = "fixed";
  field.style.opacity = "0";
  document.body.appendChild(field);
  field.select();
  document.execCommand("copy");
  field.remove();
}

copyButtons.forEach((button) => {
  button.addEventListener("click", async () => {
    const command = button.dataset.copy || "";
    const originalLabel = button.textContent;

    try {
      await copyText(command);
      button.textContent = "Copié";
    } catch {
      button.textContent = "Erreur";
    }

    window.setTimeout(() => {
      button.textContent = originalLabel;
    }, 1600);
  });
});

if ("IntersectionObserver" in window) {
  const revealObserver = new IntersectionObserver(
    (entries, observer) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    },
    { threshold: 0.12, rootMargin: "0px 0px -30px" },
  );

  revealItems.forEach((item) => revealObserver.observe(item));

  const navigationObserver = new IntersectionObserver(
    (entries) => {
      const current = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];

      if (!current) return;
      navigationLinks.forEach((link) => {
        link.classList.toggle("is-active", link.getAttribute("href") === `#${current.target.id}`);
      });
    },
    { threshold: [0.2, 0.45, 0.7], rootMargin: "-20% 0px -55%" },
  );

  sections.forEach((section) => navigationObserver.observe(section));
} else {
  revealItems.forEach((item) => item.classList.add("is-visible"));
}

const year = document.querySelector("#current-year");
if (year) year.textContent = String(new Date().getFullYear());
