// Copie de la commande de compilation et année du pied de page. Le site
// reste entièrement utilisable sans JavaScript.

document.querySelectorAll(".copy-button").forEach((button) => {
  button.hidden = false;
  button.addEventListener("click", async () => {
    const feedback = button.closest(".build-card")?.querySelector(".copy-feedback");
    if (!feedback) return;
    try {
      await navigator.clipboard.writeText(button.dataset.copy);
      feedback.textContent = "Commande copiée dans le presse-papiers.";
      button.textContent = "Copié";
      setTimeout(() => { button.textContent = "Copier"; }, 2000);
    } catch {
      feedback.textContent = "Copie indisponible. Sélectionnez la commande pour la copier.";
    }
  });
});

const year = document.querySelector("#current-year");
if (year) year.textContent = String(new Date().getFullYear());
