from pathlib import Path
import sys
root=Path(sys.argv[1])
pkg=Path("app/src/main/java/br/com/saicapeta/android")
main=root/pkg/"MainActivity.java"
store=root/pkg/"SettingsStore.java"
source=main.read_text(encoding="utf-8")
data=store.read_text(encoding="utf-8")
def rep(text,old,new):
    if text.count(old)!=1:
        raise RuntimeError("Trecho inesperado ("+str(text.count(old))+"): "+old[:80])
    return text.replace(old,new,1)

source=rep(source,"import android.content.ClipData;","import android.database.Cursor;\nimport android.content.ClipData;")
source=rep(source,"import java.util.Date;","import java.util.ArrayList;\nimport java.util.LinkedHashMap;\nimport java.util.LinkedHashSet;\nimport java.util.Date;")
source=rep(source,"REQ_NOTIFICATIONS=74;","REQ_NOTIFICATIONS=74, REQ_IMPORT=75;")
source=rep(source,"    private boolean pendingActivation=false;","    private boolean pendingActivation=false;\n    private String pendingContactImport=\"\";\n    private int allowedVisible=40,deniedVisible=40;")
source=rep(source,"        for(String number:items){","        int visible=which.equals(\"allow\")?allowedVisible:deniedVisible;\n        int shown=0;\n        for(String number:items){\n            if(shown++>=visible)break;")
source=rep(source,'''        addButton(panel,which.equals("allow")?"+ Permitir um número":"+ Bloquear um número",true,()->showNumberDialog(which));
        if(which.equals("allow"))addButton(panel,"Escolher da agenda",false,()->{
            Intent pick=new Intent(Intent.ACTION_PICK,ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            try{startActivityForResult(pick,REQ_PICK);}catch(Exception e){showInfo("Agenda indisponível","Não foi possível abrir a lista de contatos.");}
        });''','''        if(items.size()>visible){
            addButton(panel,"Mostrar mais ("+(items.size()-visible)+" restantes)",false,()->{
                if(which.equals("allow"))allowedVisible+=40;else deniedVisible+=40;
                render();
            });
        }
        addButton(panel,which.equals("allow")?"+ Permitir um número":"+ Bloquear um número",true,()->showNumberDialog(which));
        if(which.equals("allow")){
            addButton(panel,"Selecionar vários contatos",false,()->startContactsImport(false));
            addButton(panel,"Adicionar todos os contatos",false,()->startContactsImport(true));
            panel.addView(txt("A importação respeita sua lista de bloqueados e não duplica números.",12,MUTED,false));
        }''')

methods=r'''    private static final class ContactEntry {
        final long id;
        final String name;
        final LinkedHashSet<String> numbers=new LinkedHashSet<>();
        ContactEntry(long id,String name){this.id=id;this.name=name;}
    }
    private ArrayList<ContactEntry> phoneContacts(){
        LinkedHashMap<Long,ContactEntry> byId=new LinkedHashMap<>();
        String[] columns={ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER};
        try(Cursor cursor=getContentResolver().query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns,
            null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY+" ASC")){
            if(cursor!=null)while(cursor.moveToNext()){
                long id=cursor.getLong(0);
                String name=cursor.getString(1),number=SettingsStore.clean(cursor.getString(2));
                if(number.replaceAll("\\D","").length()<8)continue;
                ContactEntry contact=byId.get(id);
                if(contact==null){
                    contact=new ContactEntry(id,name==null||name.trim().isEmpty()?"Sem nome":name);
                    byId.put(id,contact);
                }
                contact.numbers.add(number);
            }
        }catch(Exception e){
            showInfo("Não foi possível ler a agenda","Verifique o acesso aos contatos nas configurações do Android.");
        }
        return new ArrayList<>(byId.values());
    }
    private void startContactsImport(boolean all){
        if(!contactsOK()){
            pendingContactImport=all?"all":"multi";
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},REQ_IMPORT);
            return;
        }
        if(all)importAllContacts();else selectManyContacts();
    }
    private void selectManyContacts(){
        ArrayList<ContactEntry> contacts=phoneContacts();
        if(contacts.isEmpty()){
            showInfo("Agenda vazia","Não foram encontrados contatos com números de telefone válidos.");return;
        }
        String[] labels=new String[contacts.size()];
        boolean[] chosen=new boolean[contacts.size()];
        for(int i=0;i<contacts.size();i++){
            ContactEntry item=contacts.get(i);
            labels[i]=item.name+" • "+item.numbers.iterator().next()+
                (item.numbers.size()>1?" (mais "+(item.numbers.size()-1)+")":"");
        }
        new AlertDialog.Builder(this).setTitle("Selecione vários contatos")
            .setMultiChoiceItems(labels,chosen,(dialog,index,checked)->chosen[index]=checked)
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Adicionar selecionados",(dialog,which)->{
                ArrayList<String> numbers=new ArrayList<>();int count=0;
                for(int i=0;i<chosen.length;i++)if(chosen[i]){
                    count++;numbers.addAll(contacts.get(i).numbers);
                }
                if(count==0){showInfo("Nenhum selecionado","Marque um ou mais contatos para importar.");return;}
                int added=SettingsStore.addMany(this,"allow",numbers);
                render();
                showInfo("Importação concluída",count+" contato(s) selecionado(s). "+added+
                    " número(s) novo(s) permitido(s). Duplicados e bloqueios manuais foram preservados.");
            }).show();
    }
    private void importAllContacts(){
        ArrayList<ContactEntry> contacts=phoneContacts();
        ArrayList<String> numbers=new ArrayList<>();
        for(ContactEntry item:contacts)numbers.addAll(item.numbers);
        if(numbers.isEmpty()){
            showInfo("Agenda vazia","Não foram encontrados números para importar.");return;
        }
        confirm("Adicionar toda a agenda?", "Serão analisados "+contacts.size()+" contato(s) e "+
            numbers.size()+" número(s). Números já permitidos serão ignorados e bloqueios manuais continuarão valendo. "+
            "Os dados permanecem neste celular.",()->{
                int added=SettingsStore.addMany(this,"allow",numbers);
                render();
                showInfo("Agenda importada",added+" número(s) novo(s) adicionado(s). "+
                    "Números repetidos, inválidos ou bloqueados foram ignorados.");
            });
    }
'''
source=rep(source,"    private void showNumberDialog(String which){",methods+"\n    private void showNumberDialog(String which){")
source=rep(source,'''        if(requestCode==REQ_NOTIFICATIONS){SettingsStore.setNotifications(this,success);render();}''','''        if(requestCode==REQ_NOTIFICATIONS){SettingsStore.setNotifications(this,success);render();}
        if(requestCode==REQ_IMPORT){
            String pending=pendingContactImport;pendingContactImport="";
            if(success){if("all".equals(pending))importAllContacts();else selectManyContacts();}
            else showInfo("Permissão necessária","Autorize o acesso aos contatos para importar números da agenda.");
        }''')
source=rep(source,"Sai, Capeta! • versão 1.2 beta","Sai, Capeta! • versão 1.3 beta")
main.write_text(source,encoding="utf-8")

data=rep(data,"import java.util.Calendar;","import java.util.Calendar;\nimport java.util.Collection;\nimport java.util.HashSet;\nimport java.util.Set;")
store_methods=r'''    /** Insere varios numeros em uma unica gravacao. Nao remove bloqueios manuais. */
    public static synchronized int addMany(Context c,String which,Collection<String> rawNumbers){
        if(!"allow".equals(which) && !"deny".equals(which))return 0;
        if(rawNumbers==null || rawNumbers.isEmpty())return 0;
        List<String> saved=list(c,which);
        List<String> blocked=list(c,which.equals("allow")?"deny":"allow");
        Set<String> seen=new HashSet<>(saved);
        int added=0;
        for(String raw:rawNumbers){
            String n=clean(raw);
            if(n.replaceAll("\\D","").length()<8 || seen.contains(n))continue;
            boolean conflict=false;
            for(String deny:blocked)if(same(deny,n)){conflict=true;break;}
            if(conflict)continue;
            if(!saved.isEmpty() && !seen.contains(n)){
                for(String previous:saved)if(same(previous,n)){conflict=true;break;}
            }
            if(conflict)continue;
            saved.add(n);seen.add(n);added++;
        }
        if(added>0){
            JSONArray result=new JSONArray();for(String s:saved)result.put(s);
            prefs(c).edit().putString(which,result.toString()).apply();
        }
        return added;
    }
'''
data=rep(data,"    public static void remove(Context c,String which,String raw){",store_methods+"\n    public static void remove(Context c,String which,String raw){")
store.write_text(data,encoding="utf-8")
build=root/"app/build.gradle"
gradle=build.read_text(encoding="utf-8")
gradle=rep(gradle,"versionCode 4","versionCode 5")
gradle=rep(gradle,"versionName '1.2.1-beta'","versionName '1.3-beta'")
build.write_text(gradle,encoding="utf-8")
print("Importacao multipla e agenda completa prontas")
