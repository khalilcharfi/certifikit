/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.tls.internal.der

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okio.Buffer
import okio.ByteString

internal data class Certificate(
  val tbsCertificate: TbsCertificate,
  val signatureAlgorithm: AlgorithmIdentifier,
  val signatureValue: BitString
) {
  val commonName: Any?
    get() {
      return tbsCertificate.subject
          .flatten()
          .firstOrNull { it.type == ObjectIdentifiers.commonName }
          ?.value
    }

  val organizationalUnitName: Any?
    get() {
      return tbsCertificate.subject
          .flatten()
          .firstOrNull { it.type == ObjectIdentifiers.organizationalUnitName }
          ?.value
    }

  val subjectAlternativeNames: Extension
    get() {
      return tbsCertificate.extensions.first {
        it.extnID == ObjectIdentifiers.subjectAlternativeName
      }
    }

  val basicConstraints: Extension
    get() {
      return tbsCertificate.extensions.first {
        it.extnID == ObjectIdentifiers.basicConstraints
      }
    }

  /** Returns true if the certificate was signed by [issuer]. */
  @Throws(SignatureException::class)
  fun checkSignature(issuer: PublicKey): Boolean {
    val signedData = CertificateAdapters.tbsCertificate.toDer(tbsCertificate)

    val signature = Signature.getInstance(tbsCertificate.signatureAlgorithmName)
    signature.initVerify(issuer)
    signature.update(signedData.toByteArray())
    return signature.verify(signatureValue.byteString.toByteArray())
  }

  fun toX509Certificate(): X509Certificate {
    val data = CertificateAdapters.certificate.toDer(this)
    try {
      val certificateFactory = CertificateFactory.getInstance("X.509")
      val certificates = certificateFactory.generateCertificates(Buffer().write(data).inputStream())
      return certificates.single() as X509Certificate
    } catch (e: NoSuchElementException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    } catch (e: GeneralSecurityException) {
      throw IllegalArgumentException("failed to decode certificate", e)
    }
  }
}

internal data class TbsCertificate(
  /** Version ::= INTEGER { v1(0), v2(1), v3(2) } */
  val version: Long,

  /** CertificateSerialNumber ::= INTEGER */
  val serialNumber: BigInteger,
  val signature: AlgorithmIdentifier,
  val issuer: List<List<AttributeTypeAndValue>>,
  val validity: Validity,
  val subject: List<List<AttributeTypeAndValue>>,
  val subjectPublicKeyInfo: SubjectPublicKeyInfo,

  /** UniqueIdentifier ::= BIT STRING */
  val issuerUniqueID: BitString?,

  /** UniqueIdentifier ::= BIT STRING */
  val subjectUniqueID: BitString?,

  /** Extensions ::= SEQUENCE SIZE (1..MAX) OF Extension */
  val extensions: List<Extension>
) {
  /**
   * Returns the standard name of this certificate's signature algorithm as specified by
   * [Signature.getInstance]. Typical values are like "SHA256WithRSA".
   */
  val signatureAlgorithmName: String
    get() {
      return when (signature.algorithm) {
        ObjectIdentifiers.sha256WithRSAEncryption -> "SHA256WithRSA"
        ObjectIdentifiers.sha256withEcdsa -> "SHA256withECDSA"
        else -> error("unexpected signature algorithm: ${signature.algorithm}")
      }
    }

  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + version.toInt()
    result = 31 * result + serialNumber.hashCode()
    result = 31 * result + signature.hashCode()
    result = 31 * result + issuer.hashCode()
    result = 31 * result + validity.hashCode()
    result = 31 * result + subject.hashCode()
    result = 31 * result + subjectPublicKeyInfo.hashCode()
    result = 31 * result + (issuerUniqueID?.hashCode() ?: 0)
    result = 31 * result + (subjectUniqueID?.hashCode() ?: 0)
    result = 31 * result + extensions.hashCode()
    return result
  }
}

internal data class AlgorithmIdentifier(
  val algorithm: String,
  val parameters: Any?
)

internal data class AttributeTypeAndValue(
  val type: String,
  val value: Any?
)

internal data class Validity(
  val notBefore: Long,
  val notAfter: Long
) {
  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + notBefore.toInt()
    result = 31 * result + notAfter.toInt()
    return result
  }
}

internal data class SubjectPublicKeyInfo(
  val algorithm: AlgorithmIdentifier,
  val subjectPublicKey: BitString
)

internal data class Extension(
  val extnID: String,
  val critical: Boolean,
  val extnValue: Any?
)

internal data class BasicConstraints(
  val ca: Boolean,
  val pathLenConstraint: Long?
)

/**
 * A private key. Note that this class doesn't support attributes or an embedded public key.
 *
 * ```
 * Version ::= INTEGER { v1(0), v2(1) } (v1, ..., v2)
 *
 * PrivateKeyAlgorithmIdentifier ::= AlgorithmIdentifier
 *
 * PrivateKey ::= OCTET STRING
 *
 * OneAsymmetricKey ::= SEQUENCE {
 *   version                   Version,
 *   privateKeyAlgorithm       PrivateKeyAlgorithmIdentifier,
 *   privateKey                PrivateKey,
 *   attributes            [0] Attributes OPTIONAL,
 *   ...,
 *   [[2: publicKey        [1] PublicKey OPTIONAL ]],
 *   ...
 * }
 *
 * PrivateKeyInfo ::= OneAsymmetricKey
 * ```
 */
internal data class PrivateKeyInfo(
  val version: Long, // v1(0), v2(1)
  val algorithmIdentifier: AlgorithmIdentifier, // v1(0), v2(1)
  val privateKey: ByteString
) {
  // Avoid Long.hashCode(long) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + version.toInt()
    result = 31 * result + algorithmIdentifier.hashCode()
    result = 31 * result + privateKey.hashCode()
    return result
  }
}
