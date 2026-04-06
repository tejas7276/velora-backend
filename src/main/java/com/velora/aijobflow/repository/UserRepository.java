package com.velora.aijobflow.repository;

import com.velora.aijobflow.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
    String name, String email, Pageable pageable
);
}
