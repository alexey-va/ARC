package arc.arc.hooks.ztranslator;

import arc.arc.ARC;
import fr.maxlego08.ztranslator.api.Translator;
import lombok.extern.log4j.Log4j2;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

@Log4j2
public class TranslatorHook {

    Translator translator;

    public TranslatorHook(){
        translator = getProvider(Translator.class);
    }

    public String translate(Material material){
        return translator.translate(material);
    }

    public String translate(ItemStack stack){
        return translator.translate(stack);
    }

    private  <T> T getProvider(Class<T> classz) {
        RegisteredServiceProvider<T> provider = ARC.plugin.getServer().getServicesManager().getRegistration(classz);
        if (provider == null) {
            log.warn("Unable to retrieve the provider " + classz.toString());
            return null;
        }
        provider.getProvider();
        return (T) provider.getProvider();
    }


}
