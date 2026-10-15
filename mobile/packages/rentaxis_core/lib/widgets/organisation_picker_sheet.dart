import 'package:flutter/material.dart';

/// One login per person across organisations: the same email and password can
/// open several organisations, and the backend then answers the login with
/// 409 and the candidates ([AuthState.tenantChoices], each `{tenantId,
/// tenantName}`). This asks which one to open and returns its `tenantId`, or
/// null when the sheet is dismissed.
Future<String?> showOrganisationPicker(
  BuildContext context,
  List<Map<String, dynamic>> choices, {
  required bool ar,
}) {
  return showModalBottomSheet<String>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (ctx) => OrganisationPickerSheet(choices: choices, ar: ar),
  );
}

class OrganisationPickerSheet extends StatelessWidget {
  const OrganisationPickerSheet({
    super.key,
    required this.choices,
    required this.ar,
  });

  final List<Map<String, dynamic>> choices;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.7,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 4),
                child: Text(
                  ar ? 'اختر المؤسسة' : 'Choose an organisation',
                  style: theme.textTheme.titleMedium,
                ),
              ),
              Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 8),
                child: Text(
                  ar
                      ? 'يفتح حساب الدخول الخاص بك أكثر من مؤسسة واحدة.'
                      : 'Your login opens more than one organisation.',
                  style: theme.textTheme.bodySmall,
                ),
              ),
              for (final choice in choices)
                ListTile(
                  key: ValueKey('org-choice-${choice['tenantId']}'),
                  leading: const Icon(Icons.apartment_outlined),
                  title: Text('${choice['tenantName'] ?? ''}'),
                  onTap: () =>
                      Navigator.of(context).pop(choice['tenantId'] as String?),
                ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }
}
