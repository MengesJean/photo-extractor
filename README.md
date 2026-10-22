# Photo Extractor

Application macOS en Java : on choisit une vidéo de la photothèque **Photos** dans une grille
d'aperçus, on la lit avec le son, on se place sur l'image voulue, et le bouton
**« Extraire la frame »** ajoute cette image (JPEG, pleine résolution, orientation corrigée)
directement dans Photos.

## Lancer

```sh
./run.sh
```

Prérequis : Java 21 (`/usr/libexec/java_home -v 21`) et Maven (`brew install maven`).
FFmpeg n'est pas à installer : il est fourni par JavaCV.

Au premier lancement, macOS demande l'autorisation de piloter Photos
(Réglages Système › Confidentialité et sécurité › Automatisation).

## Utilisation

| Action | Souris | Clavier |
|---|---|---|
| Lecture / pause | ▶︎ / ❚❚ | Espace |
| Image précédente / suivante | ◀︎ 1 image / 1 image ▶︎ | ← / → |
| Se déplacer | curseur | |
| Ajouter l'image affichée à Photos | Extraire la frame (en pause) | |

## Tests

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

## Fonctionnement

- `photos/PhotosLibrary` : liste les vidéos, exporte l'original et importe l'image, via `osascript`.
- `photos/ThumbnailLocator` : retrouve les vignettes que Photos garde dans sa bibliothèque.
- `ui/VideoGrid` : grille de 2 colonnes, seules les lignes visibles chargent leur vignette.
- `video/MediaDecoder` : décodage FFmpeg ; H.264 et HEVC passent par VideoToolbox (puce média du Mac),
  ce qui permet de lire la 4K à 120 images/s des iPhone récents en temps réel.
- `video/VideoPlayer` : lecture synchronisée sur le son, pause, déplacement et image par image.
- `video/FrameExtractor` : redécode l'image affichée en pleine résolution pour l'export.

## Limites connues

- Les aperçus sont des images fixes (les vignettes de Photos) ; une vidéo sans vignette affiche ▶︎.
- Les vidéos HDR (HEVC 10 bits) sont converties sans adaptation de la luminance : couleurs un peu ternes.
- Le son spatial Apple (APAC) n'est pas lu par FFmpeg : c'est la piste stéréo AAC qui est utilisée.
