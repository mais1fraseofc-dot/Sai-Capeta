from pathlib import Path
import sys

project = Path(sys.argv[1])
path = project / "app/src/main/java/br/com/saicapeta/android/MainActivity.java"
src = path.read_text(encoding="utf-8")

def change(before, after):
    global src
    if before not in src:
        raise RuntimeError("Trecho nao encontrado; verifique a versao do projeto: " + before[:100])
    src = src.replace(before, after, 1)

change('    private String search="";\n', '    private String search="";\n    private boolean pendingActivation=false;\n')

change('    @Override protected void onResume(){super.onResume();render();}',
'''    @Override protected void onResume(){
        super.onResume();
        if (pendingActivation && contactsOK() && roleOK()) {
            SettingsStore.setEnabled(this,true);
            pendingActivation=false;
        }
        render();
    }''')

change('        LinearLayout base=stack();base.setBackgroundColor(BG);',
'''        LinearLayout base=stack();base.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            base.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets safe=insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() |
                    android.view.WindowInsets.Type.displayCutout());
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return insets;
            });
        }''')

change('        if(on && !roleOK())control.addView(txt("Defina este app como filtro de chamadas do Android.",12,GOLD,false));',
'''        if(on && !roleOK())control.addView(txt("Defina este app como filtro de chamadas do Android.",12,GOLD,false));
        if(!on && !roleOK())control.addView(txt("Para ativar: selecione Sai, Capeta! e confirme em Definir como padrão na janela do Android.",12,GOLD,false));''')

change('''        if(m.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)){
            SettingsStore.setEnabled(this,true);render();return;
        }
        startActivityForResult(m.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),REQ_ROLE);''',
'''        if(m.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)){
            SettingsStore.setEnabled(this,true);pendingActivation=false;render();return;
        }
        pendingActivation=true;
        startActivityForResult(m.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),REQ_ROLE);''')

change('        if(requestCode==REQ_ROLE){if(resultCode==RESULT_OK && roleOK() && contactsOK())SettingsStore.setEnabled(this,true);render();}',
'''        if(requestCode==REQ_ROLE){
            if (roleOK() && contactsOK()) {
                SettingsStore.setEnabled(this,true);
                pendingActivation=false;
            } else if (resultCode != RESULT_OK) {
                pendingActivation=false;
                showInfo("Autorização não concluída",
                    "Selecione o círculo ao lado de Sai, Capeta! e toque em Definir como padrão. "
                    + "Depois volte e ative novamente. O app Telefone continuará fazendo suas ligações.");
            }
            render();
        }''')

path.write_text(src, encoding="utf-8")
build = project / "app/build.gradle"
gradle = build.read_text(encoding="utf-8")
if "versionCode 3" not in gradle:
    raise RuntimeError("Versao do projeto nao esperada")
gradle = gradle.replace("versionCode 3", "versionCode 4", 1).replace("versionName '1.2-beta'", "versionName '1.2.1-beta'", 1)
build.write_text(gradle, encoding="utf-8")
print("Correcoes de ativacao, barras do sistema e versao aplicadas.")
