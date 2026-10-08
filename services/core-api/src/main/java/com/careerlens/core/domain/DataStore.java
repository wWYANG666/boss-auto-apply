package com.careerlens.core.domain;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DataStore {
    @PersistenceContext private EntityManager entityManager;

    public <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    public <T> T save(T entity) { return entityManager.merge(entity); }

    public <T> Optional<T> find(Class<T> type, UUID id) {
        return Optional.ofNullable(entityManager.find(type, id));
    }

    public void remove(Object entity) { entityManager.remove(entity); }

    public int update(String jpql, Map<String, ?> parameters) {
        var query = entityManager.createQuery(jpql);
        parameters.forEach(query::setParameter);
        return query.executeUpdate();
    }

    public <T> List<T> query(String jpql, Class<T> type, Map<String, ?> parameters) {
        TypedQuery<T> query = entityManager.createQuery(jpql, type);
        parameters.forEach(query::setParameter);
        return query.getResultList();
    }

    public <T> List<T> query(String jpql, Class<T> type, Map<String, ?> parameters, int maxResults) {
        TypedQuery<T> query = entityManager.createQuery(jpql, type);
        parameters.forEach(query::setParameter);
        query.setMaxResults(maxResults);
        return query.getResultList();
    }

    public <T> Optional<T> one(String jpql, Class<T> type, Map<String, ?> parameters) {
        try {
            TypedQuery<T> query = entityManager.createQuery(jpql, type);
            parameters.forEach(query::setParameter);
            query.setMaxResults(1);
            return Optional.of(query.getSingleResult());
        } catch (NoResultException ex) {
            return Optional.empty();
        }
    }

    public long count(String jpql, Map<String, ?> parameters) {
        TypedQuery<Long> query = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(query::setParameter);
        return query.getSingleResult();
    }

    public void flush() { entityManager.flush(); }
}
