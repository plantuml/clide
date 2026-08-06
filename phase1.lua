-- Audit lecture seule : les méthodes de ce type sans aucun appelant.
local TYPE = "src/main/java/clide/core/TransactionStack.java:35:20:TransactionStack"

-- Le plafond de troncature est hérité de la connexion (100 par défaut) et
-- s'applique aussi aux résultats vus depuis Lua. On le remonte, et
-- set_max_results renvoie l'ancienne valeur, seule façon de la relire.
local cap = set_max_results(1000)
print(string.format("max_results : %s -> %s", cap.previousValue, cap.newValue))

-- clide ne voit pas les fichiers édités en dehors de lui : un audit qui lit
-- l'index sans l'avoir rafraîchi peut décrire un état périmé.
local built = rebuild("errors")
if built.report.errorCount > 0 then
  print(string.format("%d erreur(s) de compilation - audit abandonné",
      built.report.errorCount))
  return
end

local members = list_members(TYPE)
local unused, checked, unlocated = {}, 0, 0

for _, member in ipairs(members.symbols.items) do
  if member.kind == "method" then
    if member.location == nil then
      -- jdtls renvoie parfois un symbole sans position : on le compte
      -- comme non examiné plutôt que comme non appelé.
      unlocated = unlocated + 1
    else
      local refs = find_reference("method", member.location.position)
      checked = checked + 1
      if refs.locations.totalCount == 0 then
        table.insert(unused, member)
      end
    end
  end
end

print(string.format("%s : %d méthode(s) examinée(s), %d sans appelant",
    members.subject, checked, #unused))
for _, member in ipairs(unused) do
  local at = member.location.position
  print(string.format("  %s:%d:%d:%s", at.path, at.line, at.column, at.name))
end
if unlocated > 0 then
  print(string.format("(%d méthode(s) non examinée(s) : aucune position)", unlocated))
end