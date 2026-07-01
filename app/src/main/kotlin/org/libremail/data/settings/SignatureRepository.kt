// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.SignatureDao
import org.libremail.data.local.entity.SignatureEntity
import org.libremail.domain.model.Signature
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD for per-account signatures, keeping the "at most one default per account" invariant. The
 * first signature created for an account becomes its default automatically; deleting the default
 * promotes another (if any) so an account with signatures always has one to auto-insert.
 */
@Singleton
class SignatureRepository @Inject constructor(private val dao: SignatureDao) {

    fun observeForAccount(accountId: String): Flow<List<Signature>> =
        dao.observeForAccount(accountId).map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: String): Signature? = dao.getById(id)?.toDomain()

    suspend fun getDefault(accountId: String): Signature? = dao.getDefault(accountId)?.toDomain()

    /** Creates a signature; makes it the default when it is the account's first. Returns its id. */
    suspend fun create(accountId: String, name: String, html: String): String {
        val id = UUID.randomUUID().toString()
        val isFirst = dao.countForAccount(accountId) == 0
        dao.upsert(SignatureEntity(id, accountId, name, html, isDefault = isFirst))
        return id
    }

    suspend fun update(id: String, name: String, html: String) {
        val existing = dao.getById(id) ?: return
        dao.upsert(existing.copy(name = name, contentHtml = html))
    }

    suspend fun delete(id: String) {
        val existing = dao.getById(id) ?: return
        dao.delete(id)
        // If we removed the default, promote the account's first remaining signature.
        if (existing.isDefault) {
            dao.firstForAccount(existing.accountId)?.let { dao.markDefault(it.id) }
        }
    }

    suspend fun setDefault(accountId: String, id: String) = dao.setDefault(accountId, id)

    private fun SignatureEntity.toDomain() = Signature(id, accountId, name, contentHtml, isDefault)
}
