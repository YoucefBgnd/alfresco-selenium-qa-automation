package config;

import org.openqa.selenium.By;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Point d'accès unique à la configuration et aux données des tests.
 *
 * <p>Cette classe centralise :</p>
 * <ul>
 *   <li>la lecture de {@code config.properties} (environnement, comptes, options de lancement) ;</li>
 *   <li>la lecture de {@code Locators.properties} (localisation des éléments de l'interface) ;</li>
 *   <li>la résolution des variables des jeux de données ({@code ${unique}}, {@code ${cle}}).</li>
 * </ul>
 *
 * <h2>Ordre de priorité d'une propriété (du plus fort au plus faible)</h2>
 * <ol>
 *   <li>propriété système : {@code mvn test -Dheadless=true} ;</li>
 *   <li>variable d'environnement : {@code ALFRESCO_} + clé en majuscules
 *       (ex. {@code admin_pass} &rarr; {@code ALFRESCO_ADMIN_PASS}) ;</li>
 *   <li>{@code config.local.properties} : fichier local <b>ignoré par Git</b> (facultatif) ;</li>
 *   <li>{@code config.properties} : valeurs par défaut non sensibles, versionnées.</li>
 * </ol>
 *
 * <p>Les secrets (mots de passe, jetons) ne doivent jamais figurer dans un fichier versionné :
 * ils se fournissent par variable d'environnement ou par {@code config.local.properties}.</p>
 */
public class ConfigReader {

    private static final String FICHIER_CONFIG = "config.properties";

    /** Surcharge locale facultative (secrets, chemins personnels) : ignorée par Git. */
    private static final String FICHIER_CONFIG_LOCAL = "config.local.properties";

    /** Préfixe des variables d'environnement qui surchargent la configuration. */
    private static final String PREFIXE_VARIABLE_ENV = "ALFRESCO_";
    private static final String FICHIER_LOCATORS = "Locators.properties";

    /** Variable de données remplacée par une valeur unique par cas de test. */
    private static final String VARIABLE_UNIQUE = "unique";

    /** Marqueur remplacé par le nom d'un groupe dans les locators paramétrés. */
    private static final String MARQUEUR_NOM_GROUPE = "GROUP_NAME";

    /** Reconnaît les variables de la forme {@code ${nom}}. */
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    /** Dernière valeur unique émise : garantit qu'aucune valeur n'est servie deux fois. */
    private static final AtomicLong DERNIERE_VALEUR_UNIQUE = new AtomicLong();

    private final Properties configProps = new Properties();
    private final Properties locatorsProps = new Properties();

    /**
     * Charge {@code config.properties}, la surcharge locale facultative
     * {@code config.local.properties} et {@code Locators.properties} depuis le classpath.
     *
     * @throws IllegalStateException si {@code config.properties} ou {@code Locators.properties}
     *                               est introuvable ou illisible
     */
    public ConfigReader() {
        charger(FICHIER_CONFIG, configProps, true);
        charger(FICHIER_CONFIG_LOCAL, configProps, false); // écrase les valeurs par défaut
        charger(FICHIER_LOCATORS, locatorsProps, true);
    }

    /** Charge un fichier de propriétés (UTF-8) ; s'il est absent, échoue seulement s'il est obligatoire. */
    private void charger(String nomFichier, Properties cible, boolean obligatoire) {
        try (InputStream flux = getClass().getClassLoader().getResourceAsStream(nomFichier)) {
            if (flux == null) {
                if (obligatoire) {
                    throw new IllegalStateException(
                            "Fichier '" + nomFichier + "' introuvable dans src/test/resources.");
                }
                return;
            }
            try (Reader lecteur = new InputStreamReader(flux, StandardCharsets.UTF_8)) {
                cible.load(lecteur);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lecture impossible du fichier '" + nomFichier + "'.", e);
        }
    }

    // ==========================================================================
    // CONFIGURATION
    // ==========================================================================

    /**
     * Retourne la valeur d'une propriété de configuration, selon l'ordre de priorité décrit
     * dans la documentation de la classe (propriété système, variable d'environnement,
     * {@code config.local.properties}, {@code config.properties}).
     *
     * @return la valeur, ou {@code null} si la clé n'est définie nulle part
     */
    public String getProperty(String key) {
        String surcharge = System.getProperty(key);
        if (surcharge != null) {
            return surcharge;
        }
        String variableEnv = System.getenv(PREFIXE_VARIABLE_ENV + key.toUpperCase(Locale.ROOT));
        if (variableEnv != null && !variableEnv.isBlank()) {
            return variableEnv;
        }
        return configProps.getProperty(key);
    }

    /** Idem {@link #getProperty(String)}, avec une valeur par défaut si la clé est absente. */
    public String getProperty(String key, String defaultValue) {
        String valeur = getProperty(key);
        return valeur != null ? valeur : defaultValue;
    }

    /**
     * Retourne une propriété obligatoire (ex. identifiants) ; la valeur n'apparaît jamais dans le message d'erreur.
     *
     * @throws IllegalStateException si la clé n'est définie nulle part ou est vide
     */
    public String getRequiredProperty(String key) {
        String valeur = getProperty(key);
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalStateException("Propriété obligatoire manquante : '" + key
                    + "'. Définissez la variable d'environnement " + PREFIXE_VARIABLE_ENV
                    + key.toUpperCase(Locale.ROOT) + " ou la clé dans config.local.properties.");
        }
        return valeur;
    }

    // ==========================================================================
    // DONNÉES DE TEST (data.csv)
    // ==========================================================================

    /**
     * Génère une valeur unique par cas de test, pour éviter les collisions de noms
     * (groupes, sites) entre deux exécutions ou deux cas successifs.
     *
     * @return une chaîne numérique, strictement différente à chaque appel dans la JVM
     */
    public static String unique() {
        long candidate = System.currentTimeMillis() % 100_000_000L;
        return String.valueOf(DERNIERE_VALEUR_UNIQUE.updateAndGet(
                precedente -> Math.max(candidate, precedente + 1)));
    }

    /**
     * Résout les variables d'une donnée de test :
     * <ul>
     *   <li>{@code ${unique}} est remplacé par la valeur {@code unique} fournie ;</li>
     *   <li>{@code ${cle}} est remplacé par la propriété {@code cle} de la configuration
     *       (chaîne vide si elle n'existe pas).</li>
     * </ul>
     *
     * @param texte  donnée brute lue dans le CSV (peut être {@code null})
     * @param unique valeur à injecter pour {@code ${unique}}
     * @return la donnée résolue et sans espaces superflus ; chaîne vide si {@code texte} est {@code null}
     */
    public String resoudre(String texte, String unique) {
        if (texte == null) {
            return "";
        }
        Matcher matcher = VARIABLE.matcher(texte);
        StringBuilder resultat = new StringBuilder();
        while (matcher.find()) {
            String cle = matcher.group(1);
            String remplacement = VARIABLE_UNIQUE.equals(cle) ? unique : getProperty(cle, "");
            matcher.appendReplacement(resultat, Matcher.quoteReplacement(remplacement));
        }
        matcher.appendTail(resultat);
        return resultat.toString().trim();
    }

    // ==========================================================================
    // LOCATORS
    // ==========================================================================

    /**
     * Retourne la définition brute d'un locator ({@code Locators.properties}, puis
     * {@code config.properties} en repli).
     *
     * @throws IllegalStateException si la clé n'existe dans aucun des deux fichiers
     */
    public String getLocator(String key) {
        String valeur = locatorsProps.getProperty(key);
        if (valeur == null) {
            valeur = configProps.getProperty(key);
        }
        if (valeur == null) {
            throw new IllegalStateException("Locator introuvable : '" + key + "'");
        }
        return valeur.trim();
    }

    /**
     * Construit le {@link By} d'un locator défini sous la forme
     * {@code id=...}, {@code name=...}, {@code xpath=...}, {@code css=...} ou {@code class=...}.
     */
    public By getBy(String key) {
        return parse(getLocator(key));
    }

    /**
     * Idem {@link #getBy(String)}, en remplaçant le marqueur {@code GROUP_NAME}
     * du locator par le nom de groupe fourni.
     */
    public By getBy(String key, String groupName) {
        return parse(getLocator(key).replace(MARQUEUR_NOM_GROUPE, groupName));
    }

    /**
     * Convertit une définition textuelle en {@link By}.
     * Sans préfixe reconnu : une expression débutant par {@code /} ou {@code (} est
     * interprétée comme du XPath, sinon comme un identifiant.
     */
    private By parse(String definition) {
        int separateur = definition.indexOf('=');
        if (separateur > 0) {
            String prefixe = definition.substring(0, separateur).trim().toLowerCase();
            String valeur = definition.substring(separateur + 1).trim();
            switch (prefixe) {
                case "id":
                    return By.id(valeur);
                case "name":
                    return By.name(valeur);
                case "xpath":
                    return By.xpath(valeur);
                case "css":
                    return By.cssSelector(valeur);
                case "class":
                    return By.className(valeur);
                default:
                    break; // préfixe inconnu : on tente l'interprétation implicite ci-dessous
            }
        }
        if (definition.startsWith("/") || definition.startsWith("(")) {
            return By.xpath(definition);
        }
        return By.id(definition);
    }
}
