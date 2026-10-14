# Publishing a public snapshot

Prepare source without copying private development state or Git history:

```sh
python tools/publication.py check
python tools/publication.py export --output publication/android-agent-harness-public
```

The exporter selects source/documentation paths, excludes credentials, configuration, logs, builds and private notes, refuses linked files and existing destinations, and records SHA-256 hashes in `PUBLICATION_MANIFEST.json`. It does not initialize Git, commit, push or change repository visibility.

The selected tree is checked for recognizable token/private-key patterns, private network addresses, local personal paths and unexpected binary files. Also run a dedicated secret scanner such as [Gitleaks](https://github.com/gitleaks/gitleaks) against the export:

```sh
gitleaks dir publication/android-agent-harness-public --redact
```

Review examples, filenames, links, binary asset provenance and prose manually. Neither scanner proves that arbitrary private information is absent. Do not include scanner reports containing unredacted findings.

## Git history matters

Deleting private material in a new commit does not remove it from previous commits, branches, tags or hosted artifacts. The original development checkout retains historical private setup notes. Keep that history private. Publish the exported source as a new repository/history rather than making the existing development history public.

Initialize Git inside the reviewed export. Before the first commit, configure the author name you intend to publish and your GitHub-provided no-reply email. A public commit reveals author/committer names and email addresses independently of file content. Confirm `.gitignore`, `git status`, the staged diff and repository visibility before pushing.

The public repository is `austingregoryus/Android-Agent-Harness`. Its Go module path uses that namespace; change the module declaration and imports together if publishing under a different namespace. Runtime credentials and local settings are not part of that attribution.

## Documentation selection

The public docs explain installation, architecture, protocol, automation, conversations, security and validation. Historical diaries, device flashing records, private environment details, browser snapshots and the original DOCX have been preserved under ignored `private-notes/` in the development checkout and omitted from export.

The MIT license uses the project contributor identity rather than a personal name. Third-party notices and asset provenance are included separately. Release APKs are unsigned; signing keys must remain outside Git.

The [risk and liability notice](disclaimer.md) accompanies the MIT license and is linked prominently from the README. It is a draft project notice, not an assurance of legal protection. Obtain qualified legal review for the jurisdictions and distribution model where you intend to rely on it.

The initial public snapshot uses one root commit with GitHub no-reply author metadata. Its manifest hashes describe the committed file bytes; Git checkout line-ending conversion can change local text bytes without changing content.
