Classes cobayes exécutées PAR clide.test.TestRunnerMainExecutionTest, jamais
par `ant test` lui-même.

Elles vivent hors du package `clide` pour cette seule raison : la cible
`ant test` sélectionne `--select-package clide`, qui ne descend que dans
`clide` et ses sous-packages. Les déplacer sous `clide.` ferait ramasser
`ParameterizedFailing` par la suite de clide, et le build échouerait sur un
test dont l'échec est justement le comportement attendu.
