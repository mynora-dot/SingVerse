package com.mynora.singverse.media.service;

import static com.mynora.singverse.media.model.FilenameParserDocument.DEFAULT_PROFILE_ID;

import com.mynora.singverse.media.dto.FilenameParserDtos.*;
import com.mynora.singverse.media.filename.*;
import com.mynora.singverse.media.model.FilenameParserDocument;
import com.mynora.singverse.media.model.FilenameParserDocument.*;
import com.mynora.singverse.media.repository.FilenameParserConfigRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FilenameParserConfigService {
    private final FilenameParserConfigRepository repository;
    private final com.mynora.singverse.media.repository.FilenameParserConfigMapper mapper;
    private final com.mynora.singverse.media.repository.MediaSourceMapper sources;
    private final KtvFilenameParser parser = new KtvFilenameParser();
    private record State(long revision, String updatedAt, FilenameParserDocument document) {}

    public FilenameParserConfigService(FilenameParserConfigRepository repository,
            com.mynora.singverse.media.repository.FilenameParserConfigMapper mapper,
            com.mynora.singverse.media.repository.MediaSourceMapper sources) {
        this.repository = repository; this.mapper = mapper; this.sources = sources;
    }

    /** Must be called first inside a transaction before source/profile binding changes. */
    public void lockBindings() { mapper.lockBindings(); }

    public record ProfileSnapshot(long revision, String id, String name, FilenameRuleSet rules) {}
    public ProfileSnapshot savedProfile(String id) {
        State state = state();
        Profile selected = profile(state, id);
        return new ProfileSnapshot(state.revision(), selected.id(), selected.name(),
                new FilenameRuleSet(resolve(state.document(), selected.entries())));
    }

    private State state() {
        var stored = repository.read();
        if (stored == null) return new State(0, null, FilenameParserDocument.legacy(BuiltinFilenameRules.defaults()));
        try { validate(stored.document()); }
        catch (FilenameRuleValidationException error) {
            throw new FilenameParserConfigRepository.InvalidConfigurationException(error);
        }
        return new State(stored.revision(), stored.updatedAt(), stored.document());
    }

    public Configuration read() { return legacy(state()); }
    public Workspace workspace() { return workspace(state(), null); }

    private Configuration legacy(State state) {
        return new Configuration(state.revision(), state.updatedAt(), state.document().defaultRules(), BuiltinFilenameRules.catalog());
    }

    private List<Reference> references(FilenameParserDocument document, String ruleId) {
        return document.profiles().stream().filter(p -> p.entries().stream().anyMatch(e -> e.ruleId().equals(ruleId)))
                .map(p -> new Reference(p.id(), p.name())).toList();
    }

    private Workspace workspace(State state, String changedId) {
        var document = state.document();
        var definitions = document.definitions().stream().map(d -> new DefinitionView(d.id(), d.name(), d.type(),
                d.expression(), d.builtinId(), references(document, d.id()))).toList();
        return new Workspace(state.revision(), state.updatedAt(), definitions, document.profiles(), BuiltinFilenameRules.catalog(), changedId);
    }

    private void revision(Long expected, State state) {
        if (expected == null || expected < 0 || expected == Long.MAX_VALUE)
            throw invalid(null, -1, "revision", "必须提供有效配置修订号");
        if (expected != state.revision()) throw new ResponseStatusException(HttpStatus.CONFLICT, "配置已被其他窗口修改，请加载最新配置并保留草稿");
    }

    private static FilenameRuleValidationException invalid(String ruleId, int index, String field, String detail) {
        return new FilenameRuleValidationException(ruleId, index, field, detail);
    }

    private State persist(State current, FilenameParserDocument document) {
        validate(document);
        String now = Instant.now().toString();
        repository.save(current.revision(), document, now);
        return new State(current.revision() + 1, now, document);
    }

    private void validate(FilenameParserDocument document) {
        if (document.schemaVersion() != 2) throw new IllegalStateException("Unsupported filename configuration version");
        if (document.definitions().size() > 1000) throw invalid(null, -1, "definitions", "规则库最多保存 1000 条规则");
        var ruleIds = new HashSet<String>();
        for (int i = 0; i < document.definitions().size(); i++) {
            Definition definition = document.definitions().get(i);
            if (!ruleIds.add(definition.id())) throw invalid(definition.id(), i, "id", "规则 ID 重复");
            try { new FilenameRuleSet(List.of(definition.executable(true))); }
            catch (FilenameRuleValidationException error) {
                throw invalid(error.ruleId(), i, error.field(), error.getMessage());
            }
        }
        if (document.profiles().size() > 100) throw invalid(null, -1, "profiles", "最多保存 100 个解析方案");
        var ids = new HashSet<String>();
        for (Profile profile : document.profiles()) {
            if (profile.id() == null || profile.id().isBlank() || !ids.add(profile.id()))
                throw invalid(null, -1, "profileId", "方案 ID 无效或重复");
            name(profile.name());
            resolve(document, profile.entries());
        }
        if (!ids.contains(DEFAULT_PROFILE_ID)) throw invalid(null, -1, "profiles", "默认解析方案必须保留");
    }

    private static void name(String name) {
        if (name == null || name.isBlank() || name.length() > 100)
            throw invalid(null, -1, "name", "方案名称需要 1～100 个字符");
    }

    private List<FilenameRuleDefinition> resolve(FilenameParserDocument document, List<Entry> entries) {
        if (entries == null || entries.size() > 100) throw invalid(null, -1, "entries", "方案成员列表必填，最多 100 条");
        var ids = new HashSet<String>();
        var resolved = new ArrayList<FilenameRuleDefinition>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry == null || entry.ruleId() == null || entry.enabled() == null)
                throw invalid(entry == null ? null : entry.ruleId(), i, "entries", "成员需要 ruleId 和 enabled");
            if (!ids.add(entry.ruleId())) throw invalid(entry.ruleId(), i, "entries", "同一规则不能在一个方案中重复添加");
            Definition definition = document.definitions().stream().filter(d -> d.id().equals(entry.ruleId())).findFirst().orElse(null);
            if (definition == null) throw invalid(entry.ruleId(), i, "entries", "引用的规则不存在，请加载最新规则库");
            resolved.add(definition.executable(entry.enabled()));
        }
        return List.copyOf(resolved);
    }

    private Definition definition(State state, String id) {
        return state.document().definitions().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "解析规则不存在"));
    }
    private Profile profile(State state, String id) {
        return state.document().profiles().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "解析方案不存在"));
    }

    @Transactional
    public Workspace writeDefinition(String id, RuleWrite input) {
        if (input == null || input.definition() == null) throw invalid(id, -1, "definition", "规则定义必填");
        State state = state();
        revision(input.revision(), state);
        if (id != null && definition(state, id).type() == FilenameRuleDefinition.Type.BUILTIN)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "内置规则只读，请复制为自定义规则");
        RuleInput rule = input.definition();
        if (rule.type() == FilenameRuleDefinition.Type.BUILTIN || rule.builtinId() != null)
            throw invalid(id, -1, "type", "请创建模板或正则；内置规则只能复制为自定义定义");
        String selectedId = id == null ? UUID.randomUUID().toString() : id;
        var updated = new Definition(selectedId, rule.name(), rule.type(), rule.expression(), null);
        var definitions = new ArrayList<>(state.document().definitions());
        definitions.removeIf(d -> d.id().equals(selectedId));
        definitions.addFirst(updated);
        return workspace(persist(state, new FilenameParserDocument(2, definitions, state.document().profiles())), selectedId);
    }

    @Transactional
    public Workspace deleteDefinition(String id, Long expected) {
        State state = state();
        revision(expected, state);
        if (definition(state, id).type() == FilenameRuleDefinition.Type.BUILTIN)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "内置规则不能删除，可以在方案中移除或停用");
        var used = references(state.document(), id);
        if (!used.isEmpty()) throw new ReferencedRuleException(used);
        var definitions = state.document().definitions().stream().filter(d -> !d.id().equals(id)).toList();
        return workspace(persist(state, new FilenameParserDocument(2, definitions, state.document().profiles())), id);
    }

    @Transactional
    public Workspace writeProfile(String id, ProfileWrite input) {
        if (input == null || input.profile() == null) throw invalid(null, -1, "profile", "方案内容必填");
        State state = state();
        revision(input.revision(), state);
        if (id != null) profile(state, id);
        name(input.profile().name());
        resolve(state.document(), input.profile().entries());
        String selectedId = id == null ? UUID.randomUUID().toString() : id;
        var profiles = new ArrayList<>(state.document().profiles());
        var updated = new Profile(selectedId, input.profile().name().strip(), input.profile().entries());
        int index = -1;
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).id().equals(selectedId)) index = i;
        if (index < 0) profiles.add(updated); else profiles.set(index, updated);
        return workspace(persist(state, new FilenameParserDocument(2, state.document().definitions(), profiles)), selectedId);
    }

    @Transactional
    public Workspace deleteProfile(String id, Long expected) {
        lockBindings();
        State state = state();
        revision(expected, state);
        profile(state, id);
        if (DEFAULT_PROFILE_ID.equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "默认解析方案用于兼容旧接口，不能删除；可以改名或清空成员");
        var references = sources.references(id);
        if (!references.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "解析方案正被媒体来源使用，请先修改媒体来源的方案：" +
                String.join("、", references.stream().map(row -> row.get("name").toString()).toList()));
        var profiles = state.document().profiles().stream().filter(p -> !p.id().equals(id)).toList();
        return workspace(persist(state, new FilenameParserDocument(2, state.document().definitions(), profiles)), id);
    }

    public ProfilePreview previewProfile(ProfilePreviewInput input) {
        if (input == null || input.filename() == null) throw invalid(null, -1, "filename", "必须提供待解析文本");
        State state = state();
        if (input.profileId() != null) {
            if (input.entries() != null || input.revision() != null)
                throw invalid(null, -1, "profileId", "已保存方案和草稿来源不能同时提供");
            Profile profile = profile(state, input.profileId());
            return new ProfilePreview(state.revision(), profile.id(), profile.name(),
                    parser.preview(input.filename(), new FilenameRuleSet(resolve(state.document(), profile.entries()))));
        }
        revision(input.revision(), state);
        return new ProfilePreview(state.revision(), null, null,
                parser.preview(input.filename(), new FilenameRuleSet(resolve(state.document(), input.entries()))));
    }

    /** Compatibility write: omitted definitions leave the default profile, not the shared library. */
    @Transactional
    public Configuration save(Save input) {
        if (input == null) throw invalid(null, -1, "rules", "配置必填");
        State state = state();
        revision(input.revision(), state);
        var snapshot = new FilenameRuleSet(input.rules());
        var definitions = new LinkedHashMap<String, Definition>();
        state.document().definitions().forEach(d -> definitions.put(d.id(), d));
        for (FilenameRuleDefinition rule : snapshot.definitions()) {
            Definition previous = definitions.get(rule.id());
            Definition next = Definition.from(rule);
            if (previous != null && previous.type() == FilenameRuleDefinition.Type.BUILTIN && !previous.equals(next))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "内置定义只读，请使用复制后的自定义规则");
            definitions.put(rule.id(), next);
        }
        var currentDefault = profile(state, DEFAULT_PROFILE_ID);
        var entries = snapshot.definitions().stream().map(d -> new Entry(d.id(), d.enabled())).toList();
        var profiles = state.document().profiles().stream().map(p -> p.id().equals(DEFAULT_PROFILE_ID)
                ? new Profile(DEFAULT_PROFILE_ID, currentDefault.name(), entries) : p).toList();
        return legacy(persist(state, new FilenameParserDocument(2, List.copyOf(definitions.values()), profiles)));
    }

    public FilenameRulePreview preview(Preview input) {
        if (input == null || input.filename() == null) throw invalid(null, -1, "filename", "必须提供待解析文本");
        return parser.preview(input.filename(), new FilenameRuleSet(input.rules() == null ? read().rules() : input.rules()));
    }

    public static final class ReferencedRuleException extends ResponseStatusException {
        private final List<Reference> references;
        public ReferencedRuleException(List<Reference> references) {
            super(HttpStatus.CONFLICT, "规则正被方案引用，请先移除引用：" + String.join("、", references.stream().map(Reference::name).toList()));
            this.references = List.copyOf(references);
        }
        public List<Reference> references() { return references; }
    }
}
