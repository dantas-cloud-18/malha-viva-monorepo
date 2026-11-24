# Malha Viva iOS

SwiftUI, Swift 6, iOS 17+. Arquitetura MVVM com `@Observation` e async/await.

Por que MVVM e não TCA: o estado de cada tela é pequeno, a V1 não tem fluxos que justifiquem a dependência, e
`MviStore` (CoreUI) dá o mesmo ciclo intenção -> reducer -> estado do Android. TCA pode entrar depois, por feature.

## Estrutura
- `Packages/MalhaVivaKit`: um Swift Package local; `DomainLayer`, `DataLayer`, `DesignSystem`, `CoreUI` e um target `Feature*`
  por feature (espelha os módulos do Android).
- `App`: target do app. `Widgets`: extensão com a Live Activity (ActivityKit).
- `project.yml`: definição do projeto para o XcodeGen. O `.xcodeproj` é gerado e não vai para o git.

## Primeiros passos (macOS com Xcode)
```sh
brew install xcodegen swiftlint swiftformat
xcodegen generate
swift test --package-path Packages/MalhaVivaKit
swiftlint
swiftformat --lint .
```
