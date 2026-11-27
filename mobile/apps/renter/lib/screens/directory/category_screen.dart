import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/promotion_provider.dart';
import '../../widgets/business_logo.dart';

/// One directory category: the businesses a home tile leads to.
///
/// Self-fetching from the id in the path — this app's router rebuilds on
/// `authProvider` and would discard an `extra` payload. No FAB: the renter
/// shell's floating nav pill covers that corner.
class CategoryScreen extends ConsumerWidget {
  const CategoryScreen({super.key, required this.categoryId});

  final String categoryId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isAr = context.isAr;
    final l = _L(isAr);
    final async = ref.watch(directoryCategoryProvider(categoryId));
    final title = async.valueOrNull?.category.name(isAr);

    return Scaffold(
      appBar: AppBar(
        title: Text(title == null || title.isEmpty ? l.fallbackTitle : title),
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => isNotFoundError(error)
            ? EmptyState(
                icon: Icons.search_off_rounded,
                title: l.notFoundTitle,
                subtitle: l.notFoundMessage,
              )
            : ErrorState(
                message: l.loadFailed,
                onRetry: () =>
                    ref.invalidate(directoryCategoryProvider(categoryId)),
              ),
        data: (detail) {
          // An empty id would route to `/directory/business/`.
          final businesses =
              detail.businesses.where((b) => b.id.isNotEmpty).toList();
          if (businesses.isEmpty) {
            return EmptyState(
              icon: Icons.storefront_outlined,
              title: l.emptyTitle,
              subtitle: l.emptyMessage,
            );
          }
          return ListView.separated(
            padding: const EdgeInsets.fromLTRB(
              MiftahSpacing.page,
              12,
              MiftahSpacing.page,
              96, // clears the shell's floating nav pill
            ),
            itemCount: businesses.length,
            separatorBuilder: (_, _) =>
                const SizedBox(height: MiftahSpacing.gap),
            itemBuilder: (context, i) =>
                _BusinessRow(business: businesses[i], isAr: isAr),
          );
        },
      ),
    );
  }
}

class _BusinessRow extends StatelessWidget {
  const _BusinessRow({required this.business, required this.isAr});

  final DirectoryBusiness business;
  final bool isAr;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final description = business.description(isAr);
    return MiftahCard(
      onTap: () => context.push('/directory/business/${business.id}'),
      child: Row(
        children: [
          BusinessLogo(url: business.logoUrl, size: 44),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  business.name(isAr),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: isAr
                      ? MiftahType.ar(
                          size: 15,
                          weight: FontWeight.w700,
                          color: m.textPrimary,
                        )
                      : MiftahType.cardTitle(color: m.textPrimary),
                ),
                if (description != null && description.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    description,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: isAr
                        ? MiftahType.ar(size: 12.5, color: m.textSecondary)
                        : MiftahType.body(color: m.textSecondary),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          // chevron_right matches text direction, so it points left in RTL.
          Icon(Icons.chevron_right_rounded, color: m.textMuted),
        ],
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get fallbackTitle => ar ? 'الدليل' : 'Directory';
  String get loadFailed => ar ? 'تعذر تحميل الفئة' : 'Could not load this category';
  String get notFoundTitle =>
      ar ? 'هذه الفئة لم تعد متاحة' : 'This category is no longer available';
  String get notFoundMessage => ar
      ? 'ربما أُزيلت من الدليل. ارجع إلى الرئيسية لتصفح الفئات الأخرى.'
      : 'It may have been removed from the directory. Head back home to browse the others.';
  String get emptyTitle =>
      ar ? 'لا توجد أنشطة تجارية هنا بعد' : 'No businesses here yet';
  String get emptyMessage => ar
      ? 'ستظهر الأنشطة التجارية في هذه الفئة هنا فور إضافتها.'
      : 'Businesses in this category will show up here once they are added.';
}
