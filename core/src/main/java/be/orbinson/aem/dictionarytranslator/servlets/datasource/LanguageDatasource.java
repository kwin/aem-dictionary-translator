package be.orbinson.aem.dictionarytranslator.servlets.datasource;

import be.orbinson.aem.dictionarytranslator.services.DictionaryService;
import com.adobe.granite.ui.components.Config;
import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.SimpleDataSource;
import com.adobe.granite.ui.components.ds.ValueMapResource;
import com.day.cq.commons.LanguageUtil;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.iterators.TransformIterator;
import org.apache.jackrabbit.oak.spi.security.authorization.accesscontrol.AccessControlConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestDispatcherOptions;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.Collator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

/**
 * Granite UI data source for languages in the context of a dictionary.
 * It supports the following configuration properties:
 * <ul>
 * <li>{@code hideNonDictionaryLanguages} if set to true, only languages that are available in the dictionary are shown, if false all languages which are not yet in the dictionary are shown</li>
 * <li>{@code emitTextFieldResources} if set to true resources of type {@code granite/ui/components/coral/foundation/form/textfield} with {@code fieldLabel} and {@code name} properties for a 
 * text field are emitted, otherwise resources of empty type {@code text} and {@code value} properties for usage inside a select field
 * </ul>
 * All available languages are retrieved from {@code wcm/core/resources/languages} (by leveraging the {@code cq/gui/components/common/datasources/languages} data source).
 */
@Component(service = Servlet.class)
@SlingServletResourceTypes(
        resourceTypes = "aem-dictionary-translator/datasource/dictionary-language",
        methods = "GET"
)
public class LanguageDatasource extends SlingSafeMethodsServlet {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(LanguageDatasource.class);

    @Reference
    transient DictionaryService dictionaryService;

    /**
     * Returns the languages contained in the dictionary at the given path.
     * @param resourceResolver
     * @param dictionaryPath
     * @return a set of language/country codes
     */
    private Set<String> getDictionaryLanguages(ResourceResolver resourceResolver, String dictionaryPath) {
        return dictionaryService.getLanguagesForPath(resourceResolver, dictionaryPath).keySet().stream()
                .collect(Collectors.toSet());
    }

    /**
     * Returns all available languages.
     * @param resourceResolver
     * @param dictionaryPath
     * @return a map of language/country codes and their display names
     * @throws IOException 
     * @throws ServletException 
     */
    private Map<String, String> getAllAvailableLanguages(SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response) throws ServletException, IOException {
        // TranslationConfig.getLanguages(ResourceResolver) does never return the country label, 
        // therefore use the data source which is also used in the Page Properties dialog (Advanced Tab in Language)
        RequestDispatcherOptions options = new RequestDispatcherOptions();
        options.setForceResourceType("cq/gui/components/common/datasources/languages");
        request.getRequestDispatcher(request.getResource(), options).include(request, response);
        DataSource dataSource = (DataSource) request.getAttribute(DataSource.class.getName());
        List<ValueTextResource> resources = IteratorUtils.toList(new TransformIterator<>(dataSource.iterator(), r -> ValueTextResource.fromResource(request.getLocale(), r)));
        return toLanguageMap(resources);
    }

    private static Map<String, String> toLanguageMap(List<ValueTextResource> resources) {
        return resources.stream()
                // the upstream data source does not filter access control child resource
                .filter(r -> !AccessControlConstants.REP_POLICY.equals(r.getValue()))
                .collect(Collectors.toMap(
                ValueTextResource::getValue,
                ValueTextResource::getText,
                (oldValue, newValue) -> {
                            LOG.warn("Duplicate language/country code: {}", oldValue);
                            return oldValue;
                }));
    }

    @Override
    protected void doGet(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response) throws ServletException, IOException {
        // populate language map and filter
        String dictionaryPath = request.getRequestPathInfo().getSuffix();
        if (dictionaryPath == null) {
            throw new IllegalArgumentException("This data source must always be called with a dictionary path as request suffix");
        }
        Map<String, String> languageMap = getAllAvailableLanguages(request, response);
        Set<String> dictionaryLanguages = getDictionaryLanguages(request.getResourceResolver(), dictionaryPath);
        Predicate<String> languageFilter;
        // evaluate data source configuration
        Config dsCfg = new Config(request.getResource().getChild("datasource"));
        if (dsCfg.get("hideNonDictionaryLanguages", false)) {
            languageFilter = dictionaryLanguages::contains;
        } else { 
            languageFilter = l -> !dictionaryLanguages.contains(l);
        }
        // convert to list of resources
        boolean emitTextFieldResources = dsCfg.get("emitTextFieldResources", false);
        List<OrderedValueMapResource> resourceList = languageMap.entrySet().stream()
                .filter(e -> languageFilter.test(e.getKey()))
                .map(e -> {
                    if (emitTextFieldResources) {
                        return TextFieldResource.create(request.getLocale(), request.getResourceResolver(), e.getKey(), e.getValue());
                    } else {
                        return ValueTextResource.create(request.getLocale(), request.getResourceResolver(), e.getKey(), e.getValue());
                    }
                })
                .collect(Collectors.toList());
        // sort by display names
        Collections.sort(resourceList);
        // create data source (only accepts iterator over Resource, not of subclasses so we need to transform)
        DataSource dataSource = new SimpleDataSource(new TransformIterator<>(resourceList.iterator(), r -> (Resource) r));
        request.setAttribute(DataSource.class.getName(), dataSource);
    }

    private abstract static class OrderedValueMapResource extends ValueMapResource implements Comparable<OrderedValueMapResource> {

        private final Collator collator;
        
        protected OrderedValueMapResource(Locale locale, ResourceResolver resourceResolver, String resourceType, ValueMap vm) {
            super(resourceResolver, "", resourceType, vm);
            collator = Collator.getInstance(locale);
        }

        abstract String getLabel();
 
        @Override
        public int compareTo(OrderedValueMapResource o) {
            return collator.compare(getLabel(), o.getLabel());
        }
    }
    
    private static class TextFieldResource extends OrderedValueMapResource{

        public static TextFieldResource create(Locale locale, ResourceResolver resolver, String value, String text) {
            ValueMap valueMap = new ValueMapDecorator(Map.of("fieldLabel", text + " (" + value + ")", "name", value));
            return new TextFieldResource(locale, resolver, valueMap);
        }

        private TextFieldResource(Locale locale, ResourceResolver resolver, ValueMap valueMap) {
             super(locale, resolver, "granite/ui/components/coral/foundation/form/textfield", valueMap);
        }

        String getLabel() {
            return getValueMap().get("fieldLabel", String.class);
        }

    }

    /**
     * Value exposes the language tag while text exposes the display name (label) of the language
     */
    private static class ValueTextResource extends OrderedValueMapResource {
        public static ValueTextResource fromResource(Locale locale, Resource resource) {
            return new ValueTextResource(locale, resource.getResourceResolver(), resource.getValueMap());
        }
        
        public static ValueTextResource create(Locale locale, ResourceResolver resolver, String value, String text) {
            ValueMap valueMap = new ValueMapDecorator(Map.of("value", value, "text", text + " (" + value + ")"));
            return new ValueTextResource(locale, resolver, valueMap);
        }

        private ValueTextResource(Locale locale, ResourceResolver resolver, ValueMap valueMap) {
             super(locale, resolver, "nt:unstructured", valueMap);
        }

        public String getText() {
            return getValueMap().get("text", String.class);
        }

        public String getValue() {
            return getValueMap().get("value", String.class);
        }

        @Override
        String getLabel() {
            return getText();
        }
        
    }
}
