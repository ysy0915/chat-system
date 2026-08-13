package com.example.chat.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Mapper 接口契约测试 — 替代原 8 个"类存在验证"空壳测试（Class.forName 型），
 * 用反射校验每个 Mapper 接口的方法签名与 SQL 注解完整性。
 */
class MapperContractTest {

    static Stream<Class<?>> mappers() {
        return Stream.of(
                AttachmentRepository.class,
                DebateRecordRepository.class,
                MediaGenRecordRepository.class,
                MessageRepository.class,
                ModelConfigRepository.class,
                OnlineCountRepository.class,
                TreeHoleRepository.class,
                UserRepository.class);
    }

    @ParameterizedTest(name = "{0} 标注 @Mapper")
    @MethodSource("mappers")
    void interface_hasMapperAnnotation(Class<?> mapper) {
        assertNotNull(mapper.getAnnotation(Mapper.class),
                mapper.getSimpleName() + " 缺少 @Mapper 注解");
    }

    @ParameterizedTest(name = "{0} 方法均有 SQL 注解")
    @MethodSource("mappers")
    void method_hasSqlAnnotation(Class<?> mapper) {
        for (Method method : mapper.getDeclaredMethods()) {
            boolean hasSql = method.isAnnotationPresent(Select.class)
                    || method.isAnnotationPresent(Insert.class)
                    || method.isAnnotationPresent(Update.class)
                    || method.isAnnotationPresent(Delete.class);
            assertNotNull(hasSql ? Boolean.TRUE : null,
                    mapper.getSimpleName() + "#" + method.getName() + " 缺少 SQL 注解");
        }
    }

    @ParameterizedTest(name = "{0} 多参数方法均标注 @Param")
    @MethodSource("mappers")
    void multiParamMethod_paramsAnnotated(Class<?> mapper) {
        for (Method method : mapper.getDeclaredMethods()) {
            if (method.getParameterCount() <= 1) {
                continue;
            }
            for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                assertNotNull(parameter.getAnnotation(Param.class),
                        mapper.getSimpleName() + "#" + method.getName()
                                + " 参数 " + parameter.getName() + " 缺少 @Param 注解");
            }
        }
    }

    @ParameterizedTest(name = "{0} SQL 注解值非空")
    @MethodSource("mappers")
    void sqlAnnotation_valueNotEmpty(Class<?> mapper) {
        for (Method method : mapper.getDeclaredMethods()) {
            String sql = sqlValue(method);
            assertNotNull(sql, mapper.getSimpleName() + "#" + method.getName() + " SQL 为空");
            assertFalse(sql.isBlank(), mapper.getSimpleName() + "#" + method.getName() + " SQL 为空白");
        }
    }

    private static String sqlValue(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation instanceof Select select) {
                return first(select.value());
            }
            if (annotation instanceof Insert insert) {
                return first(insert.value());
            }
            if (annotation instanceof Update update) {
                return first(update.value());
            }
            if (annotation instanceof Delete delete) {
                return first(delete.value());
            }
        }
        return null;
    }

    private static String first(String[] values) {
        return values.length == 0 ? null : values[0];
    }
}
