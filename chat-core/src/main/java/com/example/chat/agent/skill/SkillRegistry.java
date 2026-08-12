package com.example.chat.agent.skill;

import com.example.chat.entity.Skill;
import com.example.chat.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册中心（Skills Registry）—— Step 3 技能自进化。
 *
 * <p>启动时从 MySQL skill_registry 加载全部启用技能到内存；
 * 技能自进化服务生成的新技能会注册进来，供 System Prompt 注入复用。</p>
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillRepository skillRepository;

    /** 技能名 -> 技能（内存注册表） */
    private final ConcurrentHashMap<String, Skill> registry = new ConcurrentHashMap<>();

    @Autowired
    public SkillRegistry(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @PostConstruct
    public void loadFromDb() {
        try {
            List<Skill> skills = skillRepository.findAllEnabled();
            for (Skill s : skills) {
                if (s.name != null && !s.name.isBlank()) {
                    registry.put(s.name, s);
                }
            }
            log.info("[SkillRegistry] 从数据库加载 {} 个技能: {}", registry.size(), registry.keySet());
        } catch (Exception e) {
            log.warn("[SkillRegistry] 技能加载失败（表可能未创建）: {}", e.getMessage());
        }
    }

    /** 按名称查找技能 */
    public Optional<Skill> find(String name) {
        return Optional.ofNullable(name == null ? null : registry.get(name));
    }

    /** 全部技能（按使用次数降序，方便注入 top N） */
    public List<Skill> allSkills() {
        List<Skill> list = new ArrayList<>(registry.values());
        list.sort(Comparator.comparing(s -> s.usageCount == null ? 0 : s.usageCount, Comparator.reverseOrder()));
        return list;
    }

    /** 是否有技能 */
    public boolean hasSkills() {
        return !registry.isEmpty();
    }

    /** 注册新技能（自进化后调用） */
    public void register(Skill skill) {
        if (skill == null || skill.name == null || skill.name.isBlank()) return;
        registry.put(skill.name, skill);
        log.info("[SkillRegistry] 注册新技能: {} (id={}, lang={})", skill.name, skill.id, skill.language);
    }

    /** 技能被使用：计数 +1（幂等，DB 失败不影响） */
    public void markUsed(String name) {
        find(name).ifPresent(s -> {
            s.usageCount = (s.usageCount == null ? 0 : s.usageCount) + 1;
            if (s.id != null) {
                try {
                    skillRepository.incrementUsage(s.id);
                } catch (Exception e) {
                    log.debug("[SkillRegistry] 技能使用计数失败: {}", e.getMessage());
                }
            }
        });
    }
}
