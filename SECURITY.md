# Sécurité

## Gestion des secrets

- Aucun mot de passe, jeton ou chemin personnel n'est versionné.
- Les identifiants sont fournis par variables d'environnement (`ALFRESCO_ADMIN_USER`, `ALFRESCO_ADMIN_PASS`)
  ou par `config.local.properties`, exclu par `.gitignore`.
- En CI, les identifiants sont à stocker dans les *GitHub Actions Secrets*.
- Les tests ne ciblent qu'un environnement de test, jamais un système de production.

## Signalement d'une vulnérabilité

Merci d'utiliser les *Security Advisories* privés de l'onglet **Security** du dépôt.
