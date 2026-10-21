package tests;

import config.ConfigReader;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Tests de gestion des groupes dans la console d'administration d'Alfresco Share :
 * création, ajout / retrait d'un sous-groupe, suppression.
 *
 * <h2>Pilotage par les données</h2>
 * Les cas de test (nominaux et d'exception) sont lus dans {@code src/test/resources/data.csv}.
 * Chaque ligne est exécutée comme un test indépendant : ouverture de Chrome, connexion
 * administrateur, déroulé du scénario, nettoyage, déconnexion.
 *
 * <h2>Résultat attendu</h2>
 * <ul>
 *   <li>{@code success} : le scénario doit aller au bout sans refus d'Alfresco ;</li>
 *   <li>{@code error} : Alfresco doit refuser l'opération à l'étape indiquée dans la colonne {@code etape}.</li>
 * </ul>
 *
 * <h2>Locators</h2>
 * Aucun sélecteur n'est écrit dans cette classe : ils sont tous dans {@code Locators.properties}
 * et récupérés via {@link ConfigReader}.
 */
public class SupprimerGroupeTest {

    // ==========================================================================
    // CONSTANTES
    // ==========================================================================

    /** Étape du scénario où Alfresco peut refuser la création d'un groupe (valeur de la colonne {@code etape}). */
    private static final String ETAPE_CREATION_GROUPE = "creation-groupe";

    /** Valeur de la colonne {@code resultat} qui indique un refus attendu. */
    private static final String RESULTAT_ERREUR = "error";

    /** Délai maximal par défaut pour l'apparition ou la disparition d'un élément. */
    private static final Duration DELAI_DEFAUT = Duration.ofSeconds(10);

    /** Délai court, pour les éléments facultatifs (confirmation, icône de repli). */
    private static final Duration DELAI_COURT = Duration.ofSeconds(4);

    /** Délai maximal (secondes) pour qu'un groupe fraîchement créé apparaisse dans la liste. */
    private static final int DELAI_APPARITION_GROUPE_SEC = 8;

    /** Délai maximal (secondes) pour que le bouton de création devienne actif. */
    private static final int DELAI_BOUTON_ACTIF_SEC = 3;

    /** Intervalle (ms) entre deux vérifications dans les attentes manuelles. */
    private static final long INTERVALLE_SONDAGE_MS = 250;

    // ==========================================================================
    // ÉTAT DU TEST
    // ==========================================================================

    private WebDriver driver;
    private WebDriverWait attente;
    private ConfigReader config;

    /**
     * Données d'un cas de test, après résolution des variables ({@code ${unique}}, {@code ${cle}}).
     *
     * @param id          identifiant du cas (ex. GRP_N01)
     * @param description libellé fonctionnel du cas
     * @param identifiant identifiant technique du groupe (peut être vide pour provoquer un refus)
     * @param nomAffiche  nom affiché du groupe
     * @param sousGroupe  nom du sous-groupe à créer puis rattacher (vide = aucun)
     * @param doublon     {@code true} pour retenter la création du même groupe (refus attendu)
     * @param resultat    {@code success} ou {@code error}
     * @param etape       étape où le refus est attendu (cas {@code error} uniquement)
     */
    private record CasDeTest(String id, String description, String identifiant, String nomAffiche,
                             String sousGroupe, boolean doublon, String resultat, String etape) {

        boolean refusAttendu() {
            return RESULTAT_ERREUR.equalsIgnoreCase(resultat);
        }

        boolean avecSousGroupe() {
            return !sousGroupe.isEmpty();
        }
    }

    /** Signale qu'Alfresco a refusé une étape ; sert à valider les cas d'exception attendus. */
    private static class EtapeRefusee extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String etape;

        EtapeRefusee(String etape, String message) {
            super(message);
            this.etape = etape;
        }
    }

    // ==========================================================================
    // CYCLE DE VIE
    // ==========================================================================

    /** Prépare la configuration et ouvre un navigateur Chrome neuf avant chaque cas. */
    @BeforeEach
    public void init() {
        log("=== Initialisation ===");
        config = new ConfigReader();
        configurerChromeDriver();

        driver = new ChromeDriver(creerOptionsChrome());
        driver.manage().window().maximize();
        attente = attendre(DELAI_DEFAUT);
    }

    /** Ferme la session Alfresco puis le navigateur, même si le test a échoué. */
    @AfterEach
    public void tearDown() {
        if (driver != null) {
            logout();
            driver.quit();
        }
    }

    /**
     * Utilise le chromedriver local si {@code chromedriver_path} pointe vers un fichier existant,
     * sinon laisse WebDriverManager télécharger la version adaptée.
     */
    private void configurerChromeDriver() {
        String chemin = config.getProperty("chromedriver_path", "").trim();
        if (!chemin.isEmpty() && new File(chemin).isFile()) {
            System.setProperty("webdriver.chrome.driver", chemin);
        } else {
            WebDriverManager.chromedriver().setup();
        }
    }

    /** Options de Chrome ; mode sans fenêtre si {@code headless=true}. */
    private ChromeOptions creerOptionsChrome() {
        ChromeOptions options = new ChromeOptions();
        if (Boolean.parseBoolean(config.getProperty("headless", "false"))) {
            options.addArguments("--headless=new", "--window-size=1920,1080");
        }
        return options;
    }

    // ==========================================================================
    // TEST
    // ==========================================================================

    /**
     * Exécute un cas de {@code data.csv} : création d'un groupe, éventuel sous-groupe,
     * suppression, puis comparaison du déroulé avec le résultat attendu.
     */
    @ParameterizedTest(name = "{0} - {1}")
    @CsvFileSource(resources = "/data.csv", delimiter = ';', numLinesToSkip = 1, encoding = "UTF-8")
    public void testGroupes(String id, String description, String identifiantCsv, String nomAfficheCsv,
                            String sousGroupeCsv, String doublonCsv, String resultat, String etape) {
        CasDeTest cas = construireCas(id, description, identifiantCsv, nomAfficheCsv,
                sousGroupeCsv, doublonCsv, resultat, etape);
        List<String> aNettoyer = new ArrayList<>();

        log("\n=== CAS " + cas.id() + " : " + cas.description() + " ===");
        loginAdmin();
        ouvrirPageGroupes();

        try {
            Optional<EtapeRefusee> refus = jouerScenario(cas, aNettoyer);
            verifierResultat(cas, refus);
        } finally {
            nettoyer(aNettoyer);
        }
    }

    /** Résout les variables du CSV et regroupe les données du cas. */
    private CasDeTest construireCas(String id, String description, String identifiantCsv, String nomAfficheCsv,
                                    String sousGroupeCsv, String doublonCsv, String resultat, String etape) {
        String unique = ConfigReader.unique();
        return new CasDeTest(
                id,
                description,
                config.resoudre(identifiantCsv, unique),
                config.resoudre(nomAfficheCsv, unique),
                config.resoudre(sousGroupeCsv, unique),
                Boolean.parseBoolean(config.resoudre(doublonCsv, unique)),
                resultat,
                etape);
    }

    /**
     * Déroule le scénario : création du groupe, doublon éventuel, sous-groupe éventuel, suppression.
     * Chaque groupe créé est ajouté à {@code aNettoyer} pour être supprimé en cas d'interruption.
     *
     * @return le refus d'Alfresco s'il y en a eu un, vide si le scénario est allé au bout
     */
    private Optional<EtapeRefusee> jouerScenario(CasDeTest cas, List<String> aNettoyer) {
        try {
            creerGroupe(cas.identifiant(), cas.nomAffiche());
            aNettoyer.add(cas.nomAffiche());
            verifierGroupeCree(cas.nomAffiche(), "Le groupe");

            if (cas.doublon()) {
                log("    tentative de création du même groupe");
                creerGroupe(cas.identifiant(), cas.nomAffiche()); // doit être refusé
            }

            if (cas.avecSousGroupe()) {
                gererSousGroupe(cas, aNettoyer);
            }

            supprimerGroupe(cas.nomAffiche());
            Assertions.assertFalse(groupeExiste(cas.nomAffiche()),
                    "Le groupe devrait avoir disparu après suppression");
            return Optional.empty();
        } catch (EtapeRefusee refus) {
            return Optional.of(refus);
        }
    }

    /** Crée le sous-groupe, le rattache au groupe parent, puis le retire (et le supprime s'il subsiste). */
    private void gererSousGroupe(CasDeTest cas, List<String> aNettoyer) {
        log("    ajouter un sous groupe");
        creerGroupe(cas.sousGroupe(), cas.sousGroupe());
        aNettoyer.add(cas.sousGroupe());
        verifierGroupeCree(cas.sousGroupe(), "Le sous-groupe");

        ajouterSousGroupeAuGroupe(cas.nomAffiche(), cas.sousGroupe());
        retirerSousGroupeDuGroupe(cas.sousGroupe());

        // Selon la version d'Alfresco, l'icône retire seulement le membre ou supprime aussi le groupe.
        if (groupeExiste(cas.sousGroupe())) {
            log("    (sous-groupe retiré du parent, il existe encore : suppression)");
            supprimerGroupe(cas.sousGroupe());
        } else {
            log("    (le sous-groupe a été supprimé avec le retrait)");
        }
    }

    /** Vérifie qu'un groupe apparaît dans la liste ; sinon l'échec liste les libellés visibles. */
    private void verifierGroupeCree(String nom, String sujet) {
        Assertions.assertTrue(attendreGroupe(nom, DELAI_APPARITION_GROUPE_SEC),
                sujet + " '" + nom + "' devrait exister après création. Libellés visibles : " + libellesGroupes());
    }

    /**
     * Compare le déroulé réel (refus ou non) au résultat attendu dans {@code data.csv}.
     * <ul>
     *   <li>cas nominal : aucun refus ne doit avoir eu lieu ;</li>
     *   <li>cas d'exception : un refus doit avoir eu lieu, à l'étape attendue.</li>
     * </ul>
     */
    private void verifierResultat(CasDeTest cas, Optional<EtapeRefusee> refus) {
        if (!cas.refusAttendu()) {
            refus.ifPresent(r -> Assertions.fail("Cas " + cas.id() + " : refus inattendu à l'étape '"
                    + r.etape + "' : " + r.getMessage()));
            log("✓ Cas nominal réussi : " + cas.id());
            return;
        }

        EtapeRefusee refusObserve = refus.orElseGet(() -> Assertions.fail("Cas " + cas.id()
                + " : une erreur était attendue à l'étape '" + cas.etape()
                + "' mais le déroulé s'est terminé sans refus."));
        Assertions.assertEquals(cas.etape(), refusObserve.etape,
                "Cas " + cas.id() + " : le refus n'a pas eu lieu à l'étape attendue.");
        log("✓ Cas d'exception réussi (refus attendu à '" + refusObserve.etape + "') : "
                + refusObserve.getMessage());
    }

    // ==========================================================================
    // CONNEXION / DÉCONNEXION
    // ==========================================================================

    /** Ouvre Alfresco Share et se connecte avec le compte administrateur (variables d'environnement ou configuration locale). */
    private void loginAdmin() {
        String utilisateur = config.getRequiredProperty("admin_user");
        String motDePasse = config.getRequiredProperty("admin_pass"); // jamais affiché dans les logs

        driver.get(config.getRequiredProperty("url"));
        WebElement champUtilisateur = attente.until(
                ExpectedConditions.visibilityOfElementLocated(config.getBy("txt_UserName")));
        champUtilisateur.clear();
        champUtilisateur.sendKeys(utilisateur);

        WebElement champMotDePasse = driver.findElement(config.getBy("txt_Password"));
        champMotDePasse.clear();
        champMotDePasse.sendKeys(motDePasse);
        cliquer(attente.until(ExpectedConditions.presenceOfElementLocated(config.getBy("btn_Login"))));

        try {
            attendre(DELAI_DEFAUT).until(
                    ExpectedConditions.visibilityOfElementLocated(config.getBy("link_HeaderUserMenu")));
            log("✓ Connecté (" + utilisateur + ")");
        } catch (TimeoutException e) {
            Assertions.fail("Précondition : connexion administrateur impossible pour '" + utilisateur + "'");
        }
    }

    /** Ferme la session ; n'échoue jamais, pour ne pas masquer le résultat réel du test. */
    private void logout() {
        try {
            if (driver.findElements(config.getBy("link_HeaderUserMenu")).isEmpty()) {
                return; // aucune session ouverte
            }
            attente.until(ExpectedConditions.elementToBeClickable(config.getBy("link_HeaderUserMenu"))).click();
            attente.until(ExpectedConditions.elementToBeClickable(config.getBy("link_HeaderDeconnexion"))).click();
            attente.until(ExpectedConditions.visibilityOfElementLocated(config.getBy("txt_UserName")));
            log("✓ Déconnecté");
        } catch (Exception e) {
            log("  ! Déconnexion impossible : " + e.getMessage());
        }
    }

    // ==========================================================================
    // NAVIGATION
    // ==========================================================================

    /** Accède directement à la console d'administration, section Groupes. */
    private void ouvrirPageGroupes() {
        driver.get(config.getRequiredProperty("url") + "console/admin-console/groups");
        attente.until(ExpectedConditions.elementToBeClickable(config.getBy("btn_ParcourirGroupes"))).click();
        log("✓ Page Groupes ouverte");
    }

    // ==========================================================================
    // CRÉATION D'UN GROUPE
    // ==========================================================================

    /**
     * Crée un groupe via le formulaire « Nouveau groupe ».
     *
     * @param identifiant identifiant technique (vide autorisé pour tester le refus)
     * @param nomAffiche  nom affiché (vide autorisé)
     * @throws EtapeRefusee (étape {@value #ETAPE_CREATION_GROUPE}) si Alfresco refuse la création
     */
    public void creerGroupe(String identifiant, String nomAffiche) {
        log("→ Ajout groupe: '" + identifiant + "'");
        int nombreAvant = compterGroupes();

        ouvrirFormulaireNouveauGroupe();
        remplirFormulaireGroupe(identifiant, nomAffiche);
        validerFormulaireGroupe();

        // Création réussie = un groupe de plus dans la liste
        try {
            attendre(DELAI_DEFAUT).until(d -> compterGroupes() > nombreAvant);
        } catch (TimeoutException e) {
            throw new EtapeRefusee(ETAPE_CREATION_GROUPE,
                    "Création refusée par Alfresco (identifiant vide ou groupe déjà existant)");
        }
        log("✓ Groupe créé");
    }

    private void ouvrirFormulaireNouveauGroupe() {
        try {
            attente.until(ExpectedConditions.elementToBeClickable(config.getBy("btn_new_group"))).click();
        } catch (Exception e) {
            diagnostic();
            throw e;
        }
    }

    private void remplirFormulaireGroupe(String identifiant, String nomAffiche) {
        WebElement champIdentifiant = attente.until(
                ExpectedConditions.visibilityOfElementLocated(config.getBy("input_group_identifier")));
        saisir(champIdentifiant, identifiant);
        saisir(driver.findElement(config.getBy("input_group_display_name")), nomAffiche);
    }

    /** Clique sur « Créer » ; un bouton qui reste désactivé est un refus (données invalides). */
    private void validerFormulaireGroupe() {
        WebElement boutonCreer;
        try {
            boutonCreer = attente.until(ExpectedConditions.presenceOfElementLocated(config.getBy("btn_create_group")));
        } catch (Exception e) {
            diagnostic();
            throw e;
        }
        if (!attendreActif(boutonCreer, DELAI_BOUTON_ACTIF_SEC)) {
            throw new EtapeRefusee(ETAPE_CREATION_GROUPE, "Bouton de création désactivé (données invalides)");
        }
        cliquer(boutonCreer);
    }

    // ==========================================================================
    // SOUS-GROUPE
    // ==========================================================================

    /** Fait défiler jusqu'au groupe dans la colonne et le sélectionne. */
    private void selectionnerGroupe(String nomGroupe) {
        WebElement item = attente.until(
                ExpectedConditions.presenceOfElementLocated(config.getBy("group_item", nomGroupe)));
        defilerVers(item);
        cliquer(item);
    }

    /**
     * Ajoute un groupe existant comme membre d'un groupe parent (bouton « Ajouter un groupe »,
     * recherche, puis ajout depuis les résultats).
     */
    public void ajouterSousGroupeAuGroupe(String parent, String sousGroupe) {
        log("    ajouter le sous groupe au groupe");
        try {
            selectionnerGroupe(parent);
            cliquer(attente.until(ExpectedConditions.presenceOfElementLocated(config.getBy("btn_add_group_member"))));

            WebElement champRecherche = attente.until(
                    ExpectedConditions.visibilityOfElementLocated(config.getBy("groupfinder_input")));
            saisir(champRecherche, sousGroupe);
            cliquer(attente.until(ExpectedConditions.elementToBeClickable(config.getBy("groupfinder_search_btn"))));

            cliquer(attente.until(ExpectedConditions.elementToBeClickable(
                    config.getBy("groupfinder_result_add", sousGroupe))));

            // Le sous-groupe doit maintenant apparaître aussi dans la colonne des membres du parent
            attente.until(d -> d.findElements(config.getBy("group_item", sousGroupe)).size() >= 2);
        } catch (Exception e) {
            diagnostic();
            throw e;
        }
    }

    /** Retire le sous-groupe du groupe parent (confirmation gérée si Alfresco la demande). */
    public void retirerSousGroupeDuGroupe(String sousGroupe) {
        log("    supprimer le sous groupe du groupe");
        try {
            // La dernière occurrence est celle de la colonne des membres du parent
            WebElement item = attente.until(d -> {
                List<WebElement> occurrences = d.findElements(config.getBy("group_item", sousGroupe));
                return occurrences.size() >= 2 ? occurrences.get(occurrences.size() - 1) : null;
            });
            new Actions(driver).moveToElement(item).perform();
            cliquer(attente.until(ExpectedConditions.presenceOfElementLocated(
                    config.getBy("group_member_remove_icon", sousGroupe))));

            confirmerSiDemande();

            attente.until(d -> d.findElements(config.getBy("group_item", sousGroupe)).size() <= 1);
        } catch (Exception e) {
            diagnostic();
            throw e;
        }
    }

    /** Valide la boîte de confirmation de suppression si elle s'affiche ; sinon continue. */
    private void confirmerSiDemande() {
        try {
            attendre(DELAI_COURT).until(
                    ExpectedConditions.elementToBeClickable(config.getBy("btn_confirm_delete_group"))).click();
        } catch (Exception e) {
            log("      (pas de confirmation demandée)");
        }
    }

    // ==========================================================================
    // SUPPRESSION
    // ==========================================================================

    /** Supprime un groupe : sélection, clic sur l'icône de suppression, confirmation, disparition de la liste. */
    public void supprimerGroupe(String nomGroupe) {
        log("→ Suppression groupe: " + nomGroupe);
        WebElement item = attente.until(
                ExpectedConditions.presenceOfElementLocated(config.getBy("group_item", nomGroupe)));
        defilerVers(item);

        // La sélection ouvre le panneau de détail qui porte les icônes d'action
        new Actions(driver).moveToElement(item).perform();
        cliquer(item);

        cliquer(trouverIconeSuppression(nomGroupe));
        attente.until(ExpectedConditions.elementToBeClickable(config.getBy("btn_confirm_delete_group"))).click();

        attente.until(ExpectedConditions.invisibilityOfElementLocated(config.getBy("group_item", nomGroupe)));
        log("✓ Groupe supprimé");
    }

    /** Cherche l'icône dans l'élément du groupe, sinon dans le panneau de détail. */
    private WebElement trouverIconeSuppression(String nomGroupe) {
        try {
            return attendre(DELAI_COURT).until(
                    ExpectedConditions.presenceOfElementLocated(config.getBy("group_delete_icon", nomGroupe)));
        } catch (Exception e) {
            try {
                return attente.until(ExpectedConditions.presenceOfElementLocated(config.getBy("group_delete_icon_any")));
            } catch (Exception introuvable) {
                diagnostic();
                throw introuvable;
            }
        }
    }

    // ==========================================================================
    // VÉRIFICATION ET NETTOYAGE
    // ==========================================================================

    /** Indique si un groupe est affiché dans la liste (contrôle immédiat, sans attente). */
    public boolean groupeExiste(String nomGroupe) {
        return !driver.findElements(config.getBy("group_item", nomGroupe)).isEmpty();
    }

    /** Attend, au plus {@code secondes}, que le groupe apparaisse dans la liste. */
    private boolean attendreGroupe(String nomGroupe, int secondes) {
        return attendreCondition(() -> groupeExiste(nomGroupe), secondes);
    }

    /** Libellés des groupes affichés ; utile pour comprendre un échec de recherche. */
    private List<String> libellesGroupes() {
        List<String> libelles = new ArrayList<>();
        for (WebElement element : driver.findElements(config.getBy("group_items_all"))) {
            libelles.add("[" + element.getText().trim().replace("\n", " ") + "]");
        }
        return libelles;
    }

    private int compterGroupes() {
        return driver.findElements(config.getBy("group_items_all")).size();
    }

    /**
     * Supprime les groupes de test restés en place après un cas d'exception ou un échec.
     * Les groupes sont supprimés en ordre inverse de création (sous-groupes d'abord).
     * Ne lève jamais d'exception afin de ne pas masquer le résultat du test.
     */
    private void nettoyer(List<String> noms) {
        try {
            if (noms.stream().noneMatch(this::groupeExiste)) {
                return;
            }
            log("  Nettoyage des groupes de test restants...");
            ouvrirPageGroupes(); // recharge la page : ferme aussi une éventuelle boîte d'erreur
            for (int i = noms.size() - 1; i >= 0; i--) {
                if (groupeExiste(noms.get(i))) {
                    supprimerGroupe(noms.get(i));
                }
            }
        } catch (Exception e) {
            log("  ! Nettoyage impossible : " + e.getMessage());
        }
    }

    // ==========================================================================
    // UTILITAIRES
    // ==========================================================================

    /** Crée une attente explicite avec le délai demandé. */
    private WebDriverWait attendre(Duration delai) {
        return new WebDriverWait(driver, delai);
    }

    /** Vide le champ puis y saisit la valeur ; une valeur vide laisse le champ vide. */
    private void saisir(WebElement champ, String valeur) {
        champ.clear();
        if (!valeur.isEmpty()) {
            champ.sendKeys(valeur);
        }
    }

    /** Clic classique, avec repli sur un clic JavaScript si l'élément est masqué ou intercepté. */
    private void cliquer(WebElement element) {
        try {
            element.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    /** Fait défiler la page pour centrer l'élément à l'écran. */
    private void defilerVers(WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", element);
    }

    /** Un bouton YUI désactivé porte une classe « ...disabled » ; les attributs natifs sont aussi testés. */
    private boolean estDesactive(WebElement element) {
        try {
            String classes = element.getAttribute("class");
            return !element.isEnabled()
                    || element.getAttribute("disabled") != null
                    || "true".equals(element.getAttribute("aria-disabled"))
                    || (classes != null && classes.toLowerCase().contains("disabled"));
        } catch (StaleElementReferenceException e) {
            return false; // l'élément a été rechargé : on le considère actif
        }
    }

    /** Attend, au plus {@code secondes}, que l'élément soit actif ; {@code false} s'il reste désactivé. */
    private boolean attendreActif(WebElement element, int secondes) {
        return attendreCondition(() -> !estDesactive(element), secondes);
    }

    /**
     * Sonde une condition jusqu'à ce qu'elle soit vraie ou que le délai expire.
     * Contrairement à une attente explicite, elle retourne {@code false} au lieu de lever une exception.
     */
    private boolean attendreCondition(BooleanSupplier condition, int secondes) {
        long fin = System.currentTimeMillis() + secondes * 1000L;
        while (System.currentTimeMillis() < fin) {
            if (condition.getAsBoolean()) {
                return true;
            }
            pause(INTERVALLE_SONDAGE_MS);
        }
        return condition.getAsBoolean();
    }

    private void pause(long millisecondes) {
        try {
            Thread.sleep(millisecondes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Trace un message dans la console (point d'entrée unique, facile à remplacer par un vrai logger). */
    private static void log(String message) {
        System.out.println(message);
    }

    /** Liste les éléments cliquables visibles, pour retrouver le bon locator après un échec. */
    private void diagnostic() {
        Object elements = ((JavascriptExecutor) driver).executeScript(
                "return Array.from(document.querySelectorAll('button, input, a, span[role=button], span[title], img[title]'))"
                        + ".filter(e => e.offsetParent !== null)"
                        + ".map(e => e.tagName + ' | text=' + e.textContent.trim().substring(0,40)"
                        + " + ' | title=' + (e.getAttribute('title')||'') + ' | id=' + e.id + ' | class=' + e.className)"
                        + ".join('\\n');");
        log("===== ÉLÉMENTS VISIBLES =====\n" + elements);
    }
}
