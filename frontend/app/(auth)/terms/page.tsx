import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Service — Recipe AI Finder",
  description: "Terms of Service for the Recipe AI Finder application",
};

const LAST_UPDATED = "2025-01-15";
const VERSION = "1.0";

export default function TermsOfServicePage() {
  return (
    <main className="flex flex-1 justify-center bg-gray-50 px-4 py-12">
      <article className="w-full max-w-3xl rounded-2xl bg-white p-8 shadow-md sm:p-10">
        <header className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Terms of Service</h1>
          <p className="mt-2 text-sm text-gray-500">
            Last updated: <time dateTime={LAST_UPDATED}>{LAST_UPDATED}</time> ·
            Version {VERSION}
          </p>
        </header>

        <div className="space-y-8 text-sm leading-relaxed text-gray-700">
          {/* 1. Acceptable Use Policy */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              1. Acceptable Use Policy
            </h2>
            <p>
              By accessing or using Recipe AI Finder (&ldquo;the Service&rdquo;), you agree to
              use it only for lawful purposes and in accordance with these Terms.
              You shall not:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>
                Use the Service to generate, store, or distribute content that is
                unlawful, harmful, threatening, abusive, defamatory, or otherwise
                objectionable.
              </li>
              <li>
                Attempt to interfere with, compromise the system integrity, or
                circumvent any security features of the Service.
              </li>
              <li>
                Use automated scripts, bots, or crawlers to access the Service
                beyond the rate limits established in our policies.
              </li>
              <li>
                Impersonate any person or entity or misrepresent your affiliation
                with a person or entity.
              </li>
              <li>
                Upload content that infringes on intellectual property rights of
                third parties.
              </li>
            </ul>
          </section>

          {/* 2. User Responsibilities */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              2. User Responsibilities
            </h2>
            <p>As a user of the Service, you are responsible for:</p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>
                Maintaining the confidentiality and security of your account
                credentials.
              </li>
              <li>
                All activities that occur under your account, whether or not
                authorized by you.
              </li>
              <li>
                Ensuring that any ingredients or content you provide are accurate
                and do not violate applicable laws or regulations.
              </li>
              <li>
                Promptly notifying us of any unauthorized use of your account or
                any other breach of security.
              </li>
              <li>
                Complying with all applicable local, national, and international
                laws and regulations while using the Service.
              </li>
            </ul>
          </section>

          {/* 3. Intellectual Property Rights */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              3. Intellectual Property Rights for AI-Generated Content
            </h2>
            <p>
              The Service uses third-party AI models (including AWS Bedrock,
              Stability AI, and other providers) to generate recipe text and
              images based on your inputs.
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>
                AI-generated recipe text and images produced through the Service
                are provided for your personal, non-commercial use unless
                otherwise agreed in writing.
              </li>
              <li>
                You acknowledge that AI-generated content may not be eligible for
                copyright protection in all jurisdictions and that ownership
                rights may vary by applicable law.
              </li>
              <li>
                The Service, its underlying code, design, trademarks, and
                non-AI-generated content remain the exclusive property of Recipe
                AI Finder and its licensors.
              </li>
              <li>
                You grant us a limited license to store, process, and display
                your submitted ingredients and generated content solely to provide
                and improve the Service.
              </li>
            </ul>
          </section>

          {/* 4. Limitation of Liability */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              4. Limitation of Liability
            </h2>
            <p>
              To the maximum extent permitted by applicable law:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>
                The Service is provided on an &ldquo;AS IS&rdquo; and &ldquo;AS AVAILABLE&rdquo; basis
                without warranties of any kind, whether express or implied,
                including but not limited to merchantability, fitness for a
                particular purpose, or non-infringement.
              </li>
              <li>
                We shall not be liable for any indirect, incidental, special,
                consequential, or punitive damages, including loss of data,
                revenue, or profits arising from your use of the Service.
              </li>
              <li>
                AI-generated recipes are for informational purposes only. We do
                not guarantee the accuracy, safety, or suitability of any
                generated recipe. Users should exercise their own judgment
                regarding food preparation and allergens.
              </li>
              <li>
                Our total aggregate liability shall not exceed the amount you
                paid to us (if any) in the twelve (12) months preceding the
                claim.
              </li>
            </ul>
          </section>

          {/* 5. Account Termination */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              5. Account Termination
            </h2>
            <p>
              We may suspend or terminate your account under the following
              conditions:
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-5">
              <li>
                Violation of these Terms or the Acceptable Use Policy.
              </li>
              <li>
                Engagement in fraudulent or illegal activity.
              </li>
              <li>
                Extended inactivity as defined in our data retention policies.
              </li>
              <li>
                At your request through the account deletion feature, subject to
                the applicable grace period for soft deletion (30 days) or
                immediate permanent deletion upon confirmation.
              </li>
            </ul>
            <p className="mt-2">
              Upon termination, your right to access the Service ceases
              immediately. Data deletion will proceed in accordance with our
              Privacy Policy and applicable data protection regulations.
            </p>
          </section>

          {/* 6. Governing Jurisdiction */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              6. Governing Jurisdiction
            </h2>
            <p>
              These Terms shall be governed by and construed in accordance with
              the laws of [Jurisdiction Placeholder]. Any disputes arising under
              or in connection with these Terms shall be subject to the exclusive
              jurisdiction of the courts of [Jurisdiction Placeholder].
            </p>
            <p className="mt-2 text-xs text-gray-500 italic">
              Note: The governing jurisdiction will be updated to reflect the
              applicable legal jurisdiction once finalized.
            </p>
          </section>

          {/* 7. Changes to These Terms */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              7. Changes to These Terms
            </h2>
            <p>
              We reserve the right to modify these Terms at any time. Material
              changes will be communicated by updating the &ldquo;Last updated&rdquo; date
              and version number at the top of this page. Your continued use of
              the Service after changes are posted constitutes acceptance of the
              revised Terms.
            </p>
          </section>

          {/* 8. Contact */}
          <section>
            <h2 className="mb-2 text-lg font-semibold text-gray-900">
              8. Contact
            </h2>
            <p>
              If you have questions about these Terms of Service, please contact
              us at{" "}
              <a
                href="mailto:guido@asbun.io"
                className="font-medium text-[#003DA5] hover:underline"
              >
                guido@asbun.io
              </a>
              .
            </p>
          </section>
        </div>
      </article>
    </main>
  );
}
