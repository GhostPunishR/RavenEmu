const copyButtons = document.querySelectorAll(".copy-button");

copyButtons.forEach((button) => {
  button.hidden = false;
  button.addEventListener("click", async () => {
    const feedback = button.closest(".build-card")?.querySelector(".copy-feedback");
    if (!feedback) return;

    try {
      await navigator.clipboard.writeText(button.dataset.copy);
      feedback.textContent = "Commande copiée dans le presse-papiers.";
    } catch {
      feedback.textContent = "Copie indisponible. Sélectionnez la commande pour la copier manuellement.";
    }
  });
});

const year = document.querySelector("#current-year");
if (year) year.textContent = String(new Date().getFullYear());
